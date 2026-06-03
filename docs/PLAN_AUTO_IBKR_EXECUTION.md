# Plan — Moteur d'auto-exécution IBKR : fiabiliser, sécuriser, rendre réutilisable

> **Statut :** plan de travail · **Date :** 2026-06-02 · **Périmètre :** moteur d'exécution automatique IBKR (WTX et stratégies sœurs)
> **Décisions verrouillées par le porteur :**
> 1. **Entrée Limit seule, AUCUN stop-loss / bracket** — par choix délibéré. Ne jamais ajouter de SL/TP résident au broker.
> 2. **Construire un cœur d'exécution unifié maintenant**, et migrer toutes les stratégies dessus.

---

## Statut d'implémentation

| Élément | Statut |
|---|---|
| **Phase 0.1 — Détection Read-Only** (capture erreur 321/"read-only" → ERROR loggué une fois + exposé dans `/api/ibkr/auth/status` + reject typé dans `placeLimitOrder`) | ✅ **Livré** (+ test `IbGatewayNativeClientReadOnlyTest`, 7/7 verts) |
| **Phase 0.2 — Gate `native-read-only` bloquant + bascule des défauts** | ✅ **Livré** — kill-switch enforced dans `placeLimitOrder` (point d'étranglement unique → couvre toutes les stratégies). **Tous** les défauts bascules à `false` (`application.properties`, `application-ibkr-tailscale.properties`, `application-local.properties.example`, `docker-compose.release.yml`, `render-secrets-env.sh`). ⚠️ **Action opérateur** : vérifier qu'aucun env de déploiement n'exporte `RISKDESK_IBKR_NATIVE_READ_ONLY=true` (sinon trading bloqué). |
| **ADR cœur unifié** (`TradeIntent`/`ExecutionGateway`/`OrderRouter`) | ✅ **Rédigé** — [ADR_UNIFIED_EXECUTION_CORE.md](ADR_UNIFIED_EXECUTION_CORE.md) (à valider) |
| Phases 1, 2, 2b, 3 | ⏳ À planifier après validation de l'ADR |

---

## TL;DR

L'audit montre que l'« hygiène TWS API » est **déjà bien faite** (tri-state lookup, position-truth gate, barrières `openOrderEnd`, arrondi au tick, instrumentation des codes d'erreur, signature `Decimal`, ACK-timeout). Les vrais problèmes sont **structurels** :

1. 🔴 **`native-read-only` est du _dead config_** : `isNativeReadOnly()` n'a aucun appelant ; le vrai risque Read-Only est côté TWS/Gateway (erreur **321**) et **n'est pas auto-détecté au démarrage**.
2. 🟠 **Réconciliation clée sur `orderId`** (réutilisé après reconnexion) au lieu de `permId` (durable).
3. 🟠 **Pas de gate `RECONCILING` au démarrage** : un signal peut router avant la synchro broker.
4. 🟠 **Ticks codés en dur** (enum) au lieu de `minTick` runtime.
5. 🟠 **Fragmentation** : 5 chemins d'exécution dupliquent l'orchestration → non réutilisable, non maintenable.

Comme il n'y a **pas de stop par conception**, le modèle de risque est **entièrement côté app** (renversement sur signal opposé + cap de perte journalière + force-close 17:00 ET). La priorité « sécurité » est donc : **rendre le chemin sortie/reverse/flatten et la reconnexion increvables**, pas poser un stop.

---

## 1. État réel du système (audit)

### 1.1 Ce qui est déjà solide — NE PAS refaire

| Sujet | État réel | Référence |
|---|---|---|
| Tri-state lookup `FOUND/NOT_FOUND/UNAVAILABLE` | ✅ implémenté | `WtxStaleEntryReconciler`, `BrokerOrderLookup.Outcome` |
| Position-truth gate (ne pas annuler sur UNAVAILABLE) | ✅ `isInstrumentFlat()` scope compte | `WtxStaleEntryReconciler` |
| Barrières `openOrderEnd` / `completedOrdersEnd` | ✅ snapshot complet avant décision | `IbGatewayNativeClient` |
| Arrondi au tick (erreur 110) | ✅ `normalizeToTick()` avant soumission | `ExecutionManagerService` (~l.290) |
| Exchange/conId/multiplier (erreur 321 FUT) | ✅ résolus | `IbGatewayContractResolver` |
| Codes d'erreur (326/502/504/2100/201…) | ✅ `message(...)` moderne `errorTime`+`advancedOrderRejectJson` | `IbGatewayNativeClient:1640` |
| Régression Decimal 10.44+ | ✅ `tickSize(TickType, Decimal)` déjà en place | `IbGatewayNativeClient` |
| ACK timeout → `ACK_PENDING` vs `FAILED` | ✅ `order-ack-timeout-ms=15000` | `IbkrProperties` |
| Duplicate orderId (103) / nextValidId | ✅ délégué `ApiController`, gaté par `ensureConnected()` | `IbGatewayNativeClient:737` |

### 1.2 Architecture courante (WTX = WaveTrend XT)

```
IBKR Market Data (1m) → CandleClosed
  → WtxStrategyService (orchestrateur, @EventListener)        application/service/strategy/
      → filtres (HTF bias, structure, swing) → WtxSignal
      → routeToExecution → WtxExecutionBridge.submit()
          → handleEntry / handleClose / handleReverse
          → readIbkrPositionState() (réconciliation live, cache 5s)
          → IbkrOrderService → IbGatewayNativeClient.placeLimitOrder()  ← LMT DAY transmit=true, 1 jambe
  → ExecutionFillTrackingService (callbacks orderStatus/execDetails) → ACTIVE / CLOSED
  → WtxStaleEntryReconciler (@Scheduled 60s) → débloque ENTRY_SUBMITTED coincés
  → WtxNySessionCloseScheduler (cron 15-16h ET) → force-close avant clôture
```

**FSM (`domain/model/ExecutionStatus.java`) :**
`PENDING_ENTRY_SUBMISSION → ENTRY_SUBMITTED → ENTRY_PARTIALLY_FILLED → ACTIVE → VIRTUAL_EXIT_TRIGGERED → EXIT_SUBMITTED → CLOSED` · terminaux : `CANCELLED / REJECTED / FAILED`.

**Tables :** `trade_executions`, `wtx_strategy_states`, `wtx_signal_history`.

**Les 6 chemins déjà routés vers IBKR** (`domain/model/ExecutionTriggerSource.java`) :
`WTX_AUTO` · `WTXRSI_AUTO` · `QUANT_AUTO_ARM` · `PERFECT_SETUP` · `PLAYBOOK_AUTO` · `QUANT_SIM_AUTO` (+ `MENTOR_AUTO` dormant).
Ils convergent partiellement (enum `ExecutionStatus`, table `trade_executions`, `IbkrOrderService`, `ExecutionFillTrackingService`) mais **dupliquent** routage, réconciliation, idempotence, sizing, toggles.

> `QUANT_SIM_AUTO` (ajouté 2026-06-03) mirroir la **Quant 7-Gates Simulation** vers IBKR — bridge dédié
> (`IbkrQuant7GatesExecutionBridge`), entrée Limit only + flatten marketable, OFF par défaut, allowlist
> `MNQ,MCL`. À migrer vers `OrderRouter` en Phase 2b comme les autres. Spec : [PLAN_QUANT_SIM_AUTO_IBKR.md](PLAN_QUANT_SIM_AUTO_IBKR.md).

---

## 2. Modèle de risque assumé (conséquence de « pas de SL »)

WTX est une stratégie de **renversement** : la sortie d'une position est le **signal opposé** (`REVERSE` = close + open), bornée par :
- le **cap de perte journalière** (`riskdesk.wtx.max-daily-loss-usd`, par timeframe),
- le **force-close 17:00 ET** (`WtxNySessionCloseScheduler`).

Ces trois gardes sont **côté app**. **Implication acceptée :** tant que l'app est down, une position ouverte n'est gérée par personne (aucun stop résident chez le broker). La priorité fiabilité devient donc :

1. le chemin **sortie / reverse / flatten** doit toujours aboutir au broker ;
2. l'état app doit rester **synchronisé avec la vérité broker** (reconnexion, redémarrage) ;
3. **zéro double-entrée** (idempotence forte).

**Filet optionnel compatible « no-SL » (opt-in) :** annuler les **entrées Limit non remplies** sur déconnexion IBKR. N'ajoute aucun stop ; nettoie seulement les ordres dormants.

---

## 3. Les vrais risques priorisés

### 🔴 P0-A — `native-read-only` = dead config trompeur
`riskdesk.ibkr.native-read-only=true` est le **défaut** (`application.properties:69`, `IbkrProperties:27`). **`isNativeReadOnly()` (`IbkrProperties:60-61`) n'a aucun appelant** : ni passé à `controller.connect(...)`, ni vérifié dans `placeLimitOrder`. Donc le flag ne protège rien et ne débloque rien. Le **vrai** Read-Only est côté **TWS/Gateway** (erreur **321** « Read-Only mode ») et **rien ne le détecte au démarrage** — c'est le suspect n°1 « entrées non envoyées » du diagnostic.

### 🟠 P1-A — Réconciliation sur `orderId`, pas `permId`
`ExecutionFillTrackingService` matche par `findByIbkrOrderId(orderId)` (~l.211). `orderId` est scoped session et **réutilisé après reconnexion** ; `permId` (durable) est reçu dans le callback `orderStatus` mais **jeté**. Fallback `orderRef`/`executionKey` atténue, mais la clé primaire reste fragile.

### 🟠 P1-B — Pas de gate de réconciliation au démarrage
Aucun état `RECONCILING`. Au boot (surtout après crash), un signal peut router **avant** synchro broker → risque de double-soumission. Atténué par `readIbkrPositionState()` par signal (cache 5s), pas une barrière dure.

### 🟠 P1-C — `tif=DAY`, tick hardcodé, pas de `whatIfOrder`
- Entrée `DAY` (`IbGatewayNativeClient:759`) → expire à la clôture RTH (annulation parfois silencieuse).
- Ticks **codés en dur** dans `Instrument.java:10-14` au lieu de `minTick` via `reqContractDetails`.
- Pas de pré-validation `whatIfOrder` (marge/commission/`rejectReason`) ; rejet 201 découvert en asynchrone.

### 🟡 P2 — Conformité & exploitation
CME Rule 576 (`manualOrderIndicator`/`extOperator` non positionnés), procédure post-upgrade TWS/IBG non figée, observabilité d'exécution à consolider.

---

## 4. Architecture cible — le cœur d'exécution unifié

```
Stratégie (WTX, WTX+RSI, Quant, Perfect Setup, Playbook, futures…)
    │  émet un INTENT pur (domaine) :
    │    TradeIntent { instrument, timeframe, side, qty, entryLimit, source, idempotencyKey }
    │    (PAS de SL/TP : la stratégie gère ses sorties via REVERSE/flatten)
    ▼
OrderRouter (application)
    │  FSM partagée · idempotence par idempotencyKey · sizing
    │  réconciliation broker-truth (permId) · gate RECONCILING au boot
    ▼
ExecutionGateway (port domaine)  ──►  IbGatewayNativeClient (seul à parler TWS API)
                                  └─►  ReconciliationService (partagé)
```

**Principe :** la stratégie ne connaît **que** l'intent. permId, réconciliation, codes d'erreur, self-check Read-Only, post-upgrade vivent **une seule fois**, dans le cœur. **Ajouter une stratégie = produire un `TradeIntent` + s'enregistrer comme `ExecutionTriggerSource`. Rien d'autre.**

Contraintes : respecter le layering hexagonal (port domaine `ExecutionGateway`, impl infra), `TradeIntent` pur (testable hors Spring, ArchUnit OK), ne pas violer la « Simulation Decoupling Rule » de `ARCHITECTURE_PRINCIPLES.md`.

---

## 5. Plan par phases

> Chaque tâche = 1 PR `claude/…` depuis `main`, testée. Validation : `mvn -q -Dtest=… test` ciblé + `mvn -q -DskipTests compile`.

### Phase 0 — Sécuriser (rapide, 1–2 PR)
| # | Tâche | Fichiers | Critère d'acceptation |
|---|---|---|---|
| 0.1 | **Self-check Read-Only au démarrage** : au `connected()`, sonder le mode (probe `whatIfOrder` ou capter erreur 321) → **WARN visible** + exposer dans `/api/ibkr/auth/status` | `IbGatewayNativeClient`, contrôleur status | Gateway Read-Only → alerte explicite, pas d'échec silencieux (test) |
| 0.2 | **Traiter le `native-read-only` mort** : le **câbler** comme cran de sûreté logiciel (`isNativeReadOnly()` ⇒ refuse `placeLimitOrder` avec raison typée) **ou** le supprimer. Recommandé : câbler. | `IbkrProperties`, `IbGatewayNativeClient`, properties, docs | Flag a un effet vérifié par test |
| 0.3 | *(opt-in)* **Annuler les entrées Limit non remplies sur déconnexion** (filet no-SL) | `IbGatewayNativeClient`, `OrderRouter` (futur) | Déco simulée → entrées dormantes annulées, positions intactes |

### Phase 2 — Cœur unifié (le gros, maintenant — absorbe P1)
> On ne patche pas l'ancien `WtxExecutionBridge` pour le jeter ensuite : on **construit le cœur avec** les correctifs P1 intégrés, puis on migre.

| # | Tâche | Critère |
|---|---|---|
| 2.1 | Définir `TradeIntent` (domaine) + port `ExecutionGateway` + `OrderRouter` (FSM + idempotence) | Port pur, testé hors Spring, ArchUnit OK |
| 2.2 | **permId-keyed reconciliation** (ex-P1-A) intégrée d'emblée | Reconnexion réattribuant `orderId` → bonne ligne matchée (test) |
| 2.3 | **État `RECONCILING` au boot** (ex-P1-B) : bufferise/refuse les intents tant que `openOrderEnd`+positions+executions non reçus | Signal au boot avant sync → bufferisé, zéro double-soumission |
| 2.4 | **`minTick` runtime** (ex-P1-C) via `reqContractDetails`, fallback enum | Tick lu du broker ; test cohérence enum vs broker |

### Phase 2b — Migration
| # | Tâche | Critère |
|---|---|---|
| 2b.1 | **Migrer WTX** (pilote) sur `OrderRouter` ; `WtxExecutionBridge` devient producteur d'`intent` | Parité comportementale WTX (tests verts, mêmes `routingOutcome`) |
| 2b.2 | Migrer WTX+RSI, Quant, Perfect Setup, Playbook | Une seule implémentation de réconciliation/idempotence |
| 2b.3 | Doc « **Ajouter une stratégie en 1 page** » | Nouveau dev branche une stratégie sans toucher l'infra IBKR |

### Phase 3 — Durcir & exploiter (2–3 PR)
- **3.1** Journal d'exécution structuré : 1 événement par tentative (intent → submit → ack → fill → reconcile) avec code d'erreur, requêtable.
- **3.2** `whatIfOrder` optionnel (flag) : marge/commission/`rejectReason` avant l'ordre réel.
- **3.3** CME Rule 576 : `manualOrderIndicator`/`extOperator` (hardening conformité).
- **3.4** **Checklist post-upgrade TWS/IBG** dans `docs/` (revérifier Read-Only, socket clients, Bypass Precautions, certif CME 576 ; recompiler contre le bon `TwsApi.jar`) + smoke test « entrée paper MNQ 1 lot ».
- **3.5** MAJ `docs/AI_HANDOFF.md`, `ARCHITECTURE_PRINCIPLES.md`, `PROJECT_CONTEXT.md`.

### Séquence recommandée
**Phase 0 → Phase 2 (cœur, P1 inclus) → Phase 2b (migration) → Phase 3.**

---

## 6. Tests transverses (obligatoires à chaque phase)

Reconnexion avec `orderId` réattribué · gateway Read-Only · `UNAVAILABLE` pendant réconciliation · double signal au boot · entrée Limit non remplie + déconnexion · frontière de session 17:00 ET · cap de perte journalière déclenché · DST printemps/automne · week-end.

---

## 7. Annexe — carte des fichiers clés

| Rôle | Fichier |
|---|---|
| Orchestrateur WTX | `src/main/java/com/riskdesk/application/service/strategy/WtxStrategyService.java` |
| Bridge IBKR WTX | `src/main/java/com/riskdesk/application/service/strategy/WtxExecutionBridge.java` |
| Reconciler stale-entry | `src/main/java/com/riskdesk/application/service/strategy/WtxStaleEntryReconciler.java` |
| Force-close NY | `src/main/java/com/riskdesk/application/service/strategy/WtxNySessionCloseScheduler.java` |
| Fill tracking | `src/main/java/com/riskdesk/application/service/ExecutionFillTrackingService.java` |
| Client natif TWS API | `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayNativeClient.java` |
| Résolution de contrat | `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayContractResolver.java` |
| Propriétés IBKR | `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbkrProperties.java` |
| FSM exécution | `src/main/java/com/riskdesk/domain/model/ExecutionStatus.java` |
| Sources de déclenchement | `src/main/java/com/riskdesk/domain/model/ExecutionTriggerSource.java` |
| Instruments + ticks | `src/main/java/com/riskdesk/domain/model/Instrument.java` |
| Toggle auto-exécution | `src/main/java/com/riskdesk/presentation/controller/WtxStrategyController.java:76` |
| Config | `src/main/resources/application.properties` |

### Config — défauts utiles
```properties
riskdesk.wtx.enabled=true
riskdesk.ibkr.enabled=true
riskdesk.ibkr.native-read-only=true          # ⚠ dead config aujourd'hui (P0-A)
riskdesk.ibkr.order-ack-timeout-ms=15000
riskdesk.wtx.max-daily-loss-usd=500.0        # par timeframe
riskdesk.wtx.force-close-ny=true
riskdesk.wtx.preflight-mode=OFF
riskdesk.wtx.stale-entry.reconcile-interval-ms=60000
riskdesk.wtx.stale-entry.grace-seconds=120
# Auto-IBKR reste OFF par (instrument, timeframe) jusqu'à toggle explicite :
#   PUT /api/wtx/state/{instrument}/{timeframe}/auto-execution  { "enabled": true }
```
