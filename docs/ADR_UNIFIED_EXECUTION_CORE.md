# ADR — Cœur d'exécution unifié (TradeIntent / ExecutionGateway / OrderRouter)

> **Statut :** Proposé (à valider avant migration) · **Date :** 2026-06-02
> **Contexte parent :** [PLAN_AUTO_IBKR_EXECUTION.md](PLAN_AUTO_IBKR_EXECUTION.md) (Phase 2)
> **Décisions verrouillées :** entrée Limit seule, **aucun SL/bracket** · un seul cœur d'exécution pour toutes les stratégies.

---

## 1. Contexte

Cinq stratégies routent déjà vers IBKR (`ExecutionTriggerSource` : `WTX_AUTO`, `WTXRSI_AUTO`, `QUANT_AUTO_ARM`, `PERFECT_SETUP`, `PLAYBOOK_AUTO`), chacune ré-implémentant son orchestration : routage, idempotence, réconciliation broker, sizing, gestion d'erreurs. Convergence partielle seulement (`ExecutionStatus`, table `trade_executions`, `IbkrOrderService`, `ExecutionFillTrackingService`).

**Problèmes :**
- Ajouter une 6ᵉ stratégie = recopier `WtxExecutionBridge` (≈700 lignes). Non réutilisable.
- Les correctifs de fiabilité (permId, gate de démarrage, minTick) devraient exister **une fois**, pas N fois.
- Le couplage simulation/review (cf. « Simulation Decoupling Rule ») ne doit pas être reproduit.

## 2. Décision

Introduire **un cœur d'exécution unique** que toute stratégie appelle via un contrat uniforme. La stratégie produit un **intent pur** ; le cœur possède toute la mécanique broker.

```
Stratégie ──TradeIntent──► OrderRouter ──► ExecutionGateway (port) ──► IbGatewayNativeClient
                              │                                         (seul à parler TWS API)
                              └──► ReconciliationService (permId-keyed, partagé)
```

### 2.1 `TradeIntent` (domaine, pur — testable hors Spring)

```java
package com.riskdesk.domain.execution;

/** Intention d'exécution émise par une stratégie. Aucune notion de SL/TP : la stratégie
 *  gère ses sorties via un intent de sens opposé (REVERSE) ou un FLATTEN. */
public record TradeIntent(
    String idempotencyKey,        // ex: "wtx:MNQ:5m:<signalTs>:OPEN_LONG" — clé d'unicité forte
    ExecutionTriggerSource source,// WTX_AUTO, WTXRSI_AUTO, QUANT_AUTO_ARM, ...
    Instrument instrument,
    String timeframe,
    IntentKind kind,              // OPEN | REVERSE | CLOSE | FLATTEN
    OrderSide side,               // LONG | SHORT (ignoré pour FLATTEN/CLOSE)
    int quantity,                 // nombre de contrats (déjà résolu par la stratégie)
    BigDecimal limitPrice,        // prix Limit (arrondi au minTick par le cœur, pas la stratégie)
    String brokerAccountId        // compte cible (multi-compte)
) {}
```

`IntentKind` : `OPEN` (nouvelle position), `REVERSE` (close + open opposé en 2 ordres 1:1), `CLOSE` (réduit/ferme un sens), `FLATTEN` (met à plat, ex. force-close 17:00 ET / cap de perte).

### 2.2 `ExecutionGateway` (port domaine)

```java
package com.riskdesk.domain.execution.port;

/** Port broker. Une seule implémentation infra (IB Gateway). Toute la TWS API vit derrière. */
public interface ExecutionGateway {
    /** Soumet un ordre Limit. Lève une rejection typée (read-only, marge, reject, timeout). */
    BrokerOrderHandle submitLimit(BrokerOrderRequest request);

    /** Vérité broker pour un ordre : tri-state FOUND / NOT_FOUND / UNAVAILABLE (déjà en place). */
    BrokerOrderLookup lookupOrder(String accountId, String idempotencyKey);

    /** Vérité broker positions (snapshot connecté ou UNAVAILABLE). */
    PositionTruth positions(String accountId);

    /** Mode lecture seule détecté côté broker (cf. Phase 0.1). */
    boolean isReadOnly();
}
```

### 2.3 `OrderRouter` (application — FSM + idempotence + réconciliation partagées)

```java
package com.riskdesk.application.execution;

public interface OrderRouter {
    /** Point d'entrée unique de TOUTES les stratégies. Idempotent sur intent.idempotencyKey. */
    RoutingOutcome route(TradeIntent intent);
}
```

Responsabilités **centralisées une seule fois** dans `OrderRouter` :
1. **Idempotence** : refuse/déduplique sur `idempotencyKey` (remplace les `executionKey` ad-hoc).
2. **Gate `RECONCILING`** au démarrage (ex-P1-B) : bufferise/refuse tant que `openOrderEnd` + positions + executions ne sont pas synchronisés.
3. **Réconciliation `permId`-keyed** (ex-P1-A) : `permId` durable comme clé primaire, `orderId`/`idempotencyKey` en fallback.
4. **Arrondi `minTick` runtime** (ex-P1-C) : via `reqContractDetails`, fallback enum.
5. **FSM partagée** : `ExecutionStatus` (réutilise l'enum existante).
6. **Mapping d'erreurs typées** → `RoutingOutcome` (réutilise `IbkrOrderRejectionException.Kind`, + cas read-only de la Phase 0.1).

### 2.4 Ce qui NE change pas
- `ExecutionStatus` (FSM), table `trade_executions`, `ExecutionTriggerSource` : réutilisés.
- La logique métier de chaque stratégie (détection de signaux, filtres, sizing) **reste dans la stratégie**.
- Le tri-state lookup + position-truth gate (déjà bons) sont déplacés derrière `ExecutionGateway`, pas réécrits.

## 3. Comment on ajoute une stratégie (objectif d'usage)

1. La stratégie calcule sa décision et construit un `TradeIntent`.
2. Elle ajoute une valeur à `ExecutionTriggerSource`.
3. Elle appelle `orderRouter.route(intent)` et réagit au `RoutingOutcome`.

**C'est tout.** Aucune ligne d'infra IBKR, de réconciliation ou d'idempotence à écrire.

## 4. Conséquences

**Positives :** réutilisabilité (nouvelle stratégie ≈ 1 page), correctifs de fiabilité centralisés, surface de test réduite, respect du layering hexagonal (port domaine + impl infra).

**Coûts / risques :**
- Migration en plusieurs PR (WTX pilote d'abord, parité de tests obligatoire) — pas de big-bang.
- Risque de régression sur WTX pendant la bascule → exiger une parité comportementale prouvée (mêmes `routingOutcome`, mêmes transitions FSM) avant de retirer l'ancien chemin.
- `permId` nécessite une colonne sur `trade_executions` (migration Hibernate DDL `update`, additive).

## 5. Plan de migration (résumé — détail dans le plan parent)

1. Définir `TradeIntent` + `ExecutionGateway` + `OrderRouter` (squelette + tests purs).
2. Implémenter `IbGatewayExecutionGateway` (adapter sur `IbGatewayNativeClient`), permId + minTick + gate boot intégrés.
3. **Migrer WTX** (pilote) → `WtxExecutionBridge` devient producteur d'`intent`. Parité prouvée.
4. Migrer WTX+RSI, Quant, Perfect Setup, Playbook. Supprimer le code dupliqué.
5. Doc « Ajouter une stratégie en 1 page ».

## 6. Alternatives écartées
- **Garder N bridges** : statu quo, non maintenable, correctifs dupliqués.
- **Hériter d'une classe `AbstractExecutionBridge`** : couplage par héritage, moins net que le port + intent ; n'isole pas la TWS API en un point unique.

## 7. Questions ouvertes (à trancher avant l'étape 1)
- `RoutingOutcome` : réutiliser `WtxRoutingOutcome` (le généraliser) ou en créer un neutre ? *(reco : neutre + adaptateur par stratégie pour l'affichage)*
- Bufferisation du gate `RECONCILING` : rejeter (la stratégie re-signalera) ou file d'attente courte ? *(reco : rejeter avec outcome `SKIPPED_RECONCILING` — plus simple, pas d'état caché)*
