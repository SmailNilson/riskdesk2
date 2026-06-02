# Plan détaillé — Implémentation du cœur `OrderRouter` (Phase 2 · étape 2)

> **Statut :** plan de travail (validé, pré-implémentation) · **Date :** 2026-06-02
> **Parents :** [PLAN_AUTO_IBKR_EXECUTION.md](PLAN_AUTO_IBKR_EXECUTION.md) · [ADR_UNIFIED_EXECUTION_CORE.md](ADR_UNIFIED_EXECUTION_CORE.md)
> **Pré-requis livrés :** Phase 0 (kill-switch `native-read-only`) + contrat du cœur (`TradeIntent` / `OrderRouter` / `RoutingOutcome` / `RoutingResult`) — **déjà dans `main`**.
> **Décisions verrouillées :** entrée Limit seule, **aucun SL/bracket** · un seul cœur d'exécution.

---

## 0. Principe directeur + raffinement de l'ADR

L'exploration de l'existant confirme qu'il **ne faut PAS** créer le port `ExecutionGateway` séparé esquissé dans l'ADR — toute la mécanique broker existe déjà :

- **Soumission / lookup** : `IbkrOrderService.submitEntryOrder(...)` / `findOrder(...)`
- **Persistance + idempotence** : `TradeExecutionRepositoryPort` (`createIfAbsent` / `findByExecutionKey` / `save` / `findByIdForUpdate`)
- **Mapping d'erreurs typé** : `IbkrOrderRejectionException.Kind`

➡️ Le `DefaultOrderRouter` **orchestre** ces briques. Il généralise exactement le flux prouvé de `WtxExecutionBridge.handleEntry`, mais piloté par un `TradeIntent` (neutre) au lieu d'une logique WTX-spécifique.

**Action ADR :** mettre à jour `ADR_UNIFIED_EXECUTION_CORE.md` pour acter « le cœur réutilise `IbkrOrderService` + `TradeExecutionRepositoryPort` ; pas de nouveau port broker ». (PR8)

---

## 1. `DefaultOrderRouter` — design

`src/main/java/com/riskdesk/application/execution/DefaultOrderRouter.java implements OrderRouter`

**Dépendances injectées :**

| Dép | Usage |
|---|---|
| `IbkrOrderService` | `submitEntryOrder` + `findOrder` (lookup par orderRef) |
| `TradeExecutionRepositoryPort` | idempotence + persistance + lookup des lignes actives (close/reverse) |
| `ExecutionReconciler` *(livré)* | **position-truth** (`readPositionState` via `IbkrPortfolioService`) + décision `reconcile` — REQUIS pour REVERSE/CLOSE/FLATTEN (anti-stack open / anti-flatten à nu quand IBKR est plat) |
| `IbkrMarginPreflightService` *(PR6 — parité WTX)* | **gate d'affordabilité AVANT submit** quand `preflight-mode != OFF` : `canAffordOrder` refusé → décline un OPEN **avant tout effet broker** → `SKIPPED_INSUFFICIENT_MARGIN` ; pour un REVERSE, le delta net décide flatten-only plutôt qu'un open leg inabordable. À câbler à la migration WTX, sinon le gate existant est perdu. |
| `IbkrProperties` | `isEnabled()` |
| `ExecutionReadinessGate` *(livré)* | gate `RECONCILING` au démarrage |
| `MinTickResolver` *(via `IbGatewayContractResolver` — PR5)* | arrondi tick runtime |

**Algorithme `route(TradeIntent)` — cas `OPEN` (PR1) :**

```text
  // PAS de @Transactional tenue à travers le submit : la ligne PENDING + ibkrOrderId committent en
  // txns COURTES, visibles par le fill-tracker (findByIbkrOrderId) dès que l'ordre est vivant.
1. si !readiness.isReady()                         → RoutingResult.of(SKIPPED_RECONCILING)
2. si !ibkrProperties.isEnabled()                  → of(SKIPPED_IBKR_DISABLED)
3. candidate = toExecutionRecord(intent)           // status=PENDING_ENTRY_SUBMISSION,
                                                   //   normalizedEntryPrice = round(intent.limitPrice)
4. (persisted, created) = repo.createIfAbsentTracked(candidate)
   // ANTI-RACE via la contrainte unique executionKey (PAS de verrou pessimiste) : deux ticks
   // concurrents → un seul created=true ; le perdant ne soumet PAS.
   si !created → tracked(SKIPPED_DUPLICATE, persisted.id, persisted.entryOrderId)
5. try:
     sub = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
              persisted.id, persisted.executionKey, persisted.brokerAccountId,
              persisted.instrument, brokerAction(intent), persisted.quantity,
              persisted.normalizedEntryPrice))
     persisted.entryOrderId     = sub.brokerOrderId
     persisted.ibkrOrderId      = (int) sub.brokerOrderId    // REQUIS pour le fill-tracker
     persisted.entrySubmittedAt = sub.submittedAt ?? now     // âge primaire (UI + reconciler stale-entry)
     persisted.status           = ENTRY_SUBMITTED
     repo.save(persisted)
     outcome = "PendingSubmit".equals(sub.brokerOrderStatus) ? ACK_PENDING : ROUTED
     → tracked(outcome, persisted.id, sub.brokerOrderId)
   catch IbkrOrderRejectionException e:
     outcome = mapKind(e)
     // Ligne NON-TERMINALE dès que l'ordre est — ou peut être — vivant au broker :
     //   ACK_PENDING (id présent, ack tardif) ET FAILED_TIMEOUT (pas d'id, état broker INCONNU).
     // Un FAILED terminal ici autoriserait un retry sur un ordre que le broker tient peut-être ;
     // les callbacks tardifs / le reconciler doivent encore résoudre la ligne.
     persisted.status = outcome.mustTrackExecutionRow() ? ENTRY_SUBMITTED : FAILED
     si e.brokerOrderId != null:                          // ACK_PENDING — l'ordre EST au broker
       persisted.entryOrderId     = e.brokerOrderId
       persisted.ibkrOrderId      = (int) e.brokerOrderId // REQUIS : le fill-tracker localise par ibkrOrderId
       persisted.entrySubmittedAt = now                   //          (findByIbkrOrderId) — sinon la ligne est isolée
     repo.save(persisted)
     → tracked(outcome, persisted.id, e.brokerOrderId())
```
> ⚠️ `FAILED_TIMEOUT` reste **non-terminal** — cohérent avec `RoutingOutcome.mustTrackExecutionRow()` et `WtxExecutionBridge.handleEntryRejection`.
> ⚠️ Sur `ACK_PENDING` (timeout avec id), persister `entryOrderId` **et** `ibkrOrderId` avant `save` — sinon `ExecutionFillTrackingService.onOrderStatus` (qui localise via `findByIbkrOrderId`) perd les callbacks tardifs.

**`mapKind(IbkrOrderRejectionException.Kind)` :**
- `INSUFFICIENT_MARGIN` → `FAILED_INSUFFICIENT_MARGIN`
- `TIMEOUT` → `e.brokerOrderId() != null ? ACK_PENDING : FAILED_TIMEOUT`
- `BROKER_REJECT` / `CANCELLED` → **read-only ?** `FAILED_READ_ONLY` **:** `FAILED_BROKER_REJECT`
  *(le kill-switch `native-read-only` et le TWS Read-Only API remontent en `BROKER_REJECT` avec un message « read-only » → garder le diagnostic distinct)*
- sinon → `FAILED`

**`brokerAction(intent)` :** OPEN/REVERSE → `side==LONG ? "LONG" : "SHORT"` ; CLOSE/FLATTEN → côté **opposé** à la position détenue (réconcilié via `findOrder`/positions).

> Note : `BrokerEntryOrderRequest.action` est un token `"LONG"`/`"SHORT"` ; `IbGatewayBrokerGateway` mappe `"SHORT"→SELL`, sinon `BUY`.

---

## 2. Réutilisation exacte (besoin → méthode existante)

| Besoin du router | Méthode réutilisée | Fichier |
|---|---|---|
| Idempotence (check) | `repo.findByExecutionKey(key)` | `domain/execution/port/TradeExecutionRepositoryPort.java` |
| Persister PENDING (anti-race) | `repo.createIfAbsent(candidate)` | idem (impl: `JpaTradeExecutionRepositoryAdapter`) |
| Soumettre | `ibkrOrderService.submitEntryOrder(BrokerEntryOrderRequest)` → `BrokerEntryOrderSubmission` | `application/service/IbkrOrderService.java` |
| Lier au fill-tracker | `setEntryOrderId(brokerOrderId)` **ET** `setIbkrOrderId((int) brokerOrderId)` | `domain/model/TradeExecutionRecord.java` |
| Lookup / réconcile | `ibkrOrderService.findOrder(account, orderRef)` → `BrokerOrderLookup` (FOUND/NOT_FOUND/UNAVAILABLE) | `application/service/IbkrOrderService.java` |
| Erreurs typées | `IbkrOrderRejectionException.Kind` | `infrastructure/marketdata/ibkr/IbkrOrderRejectionException.java` |

**DTOs (signatures exactes) :**
```java
record BrokerEntryOrderRequest(Long executionId, String executionKey, String brokerAccountId,
                               String instrument, String action, Integer quantity, BigDecimal limitPrice)
record BrokerEntryOrderSubmission(Long brokerOrderId, String brokerOrderStatus, String orderRef, Instant submittedAt)
record BrokerOrderLookup(Outcome outcome, BrokerOrderStatusView order)   // Outcome = FOUND|NOT_FOUND|UNAVAILABLE
record BrokerOrderStatusView(Long orderId, String orderRef, String accountId, String status)
```

**Champs clés de `TradeExecutionRecord`** : `Long id` · `String executionKey` · `String instrument` · `String timeframe` · `String action` · `Integer quantity` · `ExecutionTriggerSource triggerSource` · `ExecutionStatus status` · `BigDecimal normalizedEntryPrice` · `Long entryOrderId` · `Integer ibkrOrderId` · `String brokerAccountId` · timestamps. *(⚠️ `ibkrOrderId` est `Integer`, pas `Long` — caster.)*

---

## 3. Les 3 gaps confirmés + comment on les comble

| Gap | État actuel | Correctif (PR) |
|---|---|---|
| **`minTick`** | hardcodé `Instrument.getTickSize()` ; `ContractDetails.minTick()` disponible mais **inutilisé** | **PR5** : `IbGatewayContractResolver` expose `minTick` ; `normalizeToTick` l'utilise (fallback enum) |
| **`permId`** | **jeté** ; seul `ibkrOrderId` (session, `Integer`) persisté ; `orderStatus(orderId, permId, …)` a le `permId` | **PR4** : ajouter `Long permId` sur `TradeExecutionRecord` (+entity +mapper +`findByPermId`), persister depuis `orderStatus`, réconcilier sur `permId` (fallback `orderId`/`executionKey`) |
| **Gate `RECONCILING` au boot** | **inexistant** — pas de `ApplicationReadyEvent` ni de flag global ; les stratégies routent immédiatement | **PR3** : `ExecutionReadinessGate` + `@EventListener(ApplicationReadyEvent)` qui rejoue la réconciliation des `ENTRY_SUBMITTED` au démarrage puis ouvre le gate ; `route()` renvoie `SKIPPED_RECONCILING` tant qu'il est fermé |

---

## 4. Séquence de PR (8, chacune testée et isolée)

### 🟢 Construire le cœur — **zéro impact trading** (non câblé aux stratégies)
- **PR1 — `DefaultOrderRouter` (OPEN)** : algorithme §1, idempotence, persistance, mapping d'erreurs. Gate = stub « toujours prêt ». Tests unitaires : `IbkrOrderService` mocké + repo in-memory → `ROUTED` / `SKIPPED_DUPLICATE` / `ACK_PENDING` / `FAILED_*`.
- **PR2 — REVERSE / CLOSE / FLATTEN + réconcile broker-truth** : déplacer dans le router la logique de `WtxExecutionBridge` : downgrade `REVERSE→OPEN` sur flat confirmé, skip `SKIPPED_ENTRY_IN_FLIGHT`, void phantom `ACTIVE`, décomposition REVERSE = close+open. Porter les tests de réconciliation existants.

### 🟡 Durcir le cœur — **zéro impact trading**
- **PR3 — Gate `RECONCILING`** : `ExecutionReadinessGate` + listener `ApplicationReadyEvent`. Test : route avant ready → `SKIPPED_RECONCILING`.
- **PR4 — `permId`** : schéma additif (Hibernate DDL `update`), persistance depuis `orderStatus`, réconciliation clé `permId`. Test : reconnexion réattribuant `orderId` → bonne ligne matchée.
- **PR5 — `minTick` runtime** : via `ContractDetails`, fallback enum. Test cohérence enum vs broker.

### 🔴 Migrer — **touche le trading live · relecture soignée**
- **PR6 — WTX pilote** : `WtxStrategyService` émet un `TradeIntent` → `orderRouter.route()` ; réagit au `RoutingResult` (revert état virtuel sur `SKIPPED_ENTRY_IN_FLIGHT` / `ROUTED_FLATTEN_ONLY`). `WtxExecutionBridge` vidé de sa logique broker. **Préserver le gate d'affordabilité** : câbler `IbkrMarginPreflightService` dans le router (décline l'OPEN inabordable avant submit → `SKIPPED_INSUFFICIENT_MARGIN` ; REVERSE → flatten-only sur delta net inabordable) — sinon la parité WTX casse. **Parité prouvée** : suite WTX existante verte + mêmes `routingOutcome`. Option : garder l'ancien bridge en fallback un cycle (feature flag).
- **PR7 — Autres stratégies** : WTX+RSI, Quant, Perfect Setup, Playbook émettent des `TradeIntent` ; suppression du code dupliqué. Playbook conserve `PlaybookRoutingOutcome` pour son historique (gates internes décidés **en amont** du routage), mappe vers `RoutingOutcome` au routage.

### ⚪ Finir
- **PR8 — Docs + nettoyage** : « Ajouter une stratégie en 1 page », MAJ ADR/plan (raffinement §0), suppression du code mort.

---

## 5. Stratégie de test

- **Unitaire (PR1–5)** : `DefaultOrderRouterTest` avec `IbkrOrderService` mocké (Mockito) + repo in-memory (fake implémentant `TradeExecutionRepositoryPort`) → couvre chaque `RoutingOutcome`, l'idempotence, le mapping d'exception — **sans gateway live**.
- **Réconcile (PR2)** : porter les tests de réconciliation de `WtxExecutionBridge` sur le router.
- **Parité (PR6)** : la suite WTX existante doit rester verte ; ajouter un test « même `TradeIntent` → même `outcome` qu'avant migration ».
- **ArchUnit** : `DefaultOrderRouter` en couche `application` ; `TradeIntent`/outcomes en `domain` — déjà conforme (`HexagonalArchitectureTest` vert).
- À chaque PR : `mvn -q -DskipTests compile` + classe ciblée ; **suite complète avant PR6**.

---

## 6. Garde-fous (parité WTX, argent réel)

- **PR1–5 ne sont jamais câblées** aux stratégies → impossible d'affecter le live avant **PR6**.
- **PR6** derrière relecture explicite + parité de tests + option de fallback (feature flag conservant l'ancien `WtxExecutionBridge` un cycle).
- Le **kill-switch `native-read-only`** (mergé) reste le coupe-circuit global pendant toute la migration.
- Règle « **Simulation Decoupling** » respectée : aucune nouvelle dépendance simulation côté review.

---

## 7. Estimation
PR1–PR2 = le gros du cœur · PR3–PR5 courtes et indépendantes · **PR6 = la plus sensible** (live) · PR7–PR8 = volume mécanique.
