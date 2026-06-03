# Plan — Auto-IBKR pour la Quant 7-Gates Simulation

> **Statut :** plan validé, prêt à implémenter · **Date :** 2026-06-03 · **Périmètre :** brancher l'exécution IBKR réelle sur le harnais de simulation `Quant7GatesSimulationService`, comme sur WTX / WTX-RSI / Playbook.
>
> **Décisions verrouillées par le porteur :**
> 1. **Bridge dédié** (façon `IbkrWtxRsiExecutionBridge`), pas de réutilisation du chemin `QuantAutoArm`.
> 2. **Miroir complet** entrée + sortie : l'ordre réel suit la simulation 1:1 (flatten app-driven sur SL / TP / flow-AVOID).
> 3. **Un seul sens par instrument** à la fois (jamais LONG + SHORT simultanés).
> 4. **Entrée Limit seule, AUCUN stop-loss/bracket résident broker** — cohérent avec [PLAN_AUTO_IBKR_EXECUTION.md](PLAN_AUTO_IBKR_EXECUTION.md). Le risque est géré côté app (flatten sur signal de sortie + force-close 17:00 ET).
> 5. **Allowlist d'exécution = `MNQ`, `MCL`.** MGC reste simulé mais interdit d'envoi réel ; 6E (E6) n'est pas scanné.
> 6. **OFF par défaut**, opt-in par instrument via toggle panel + master flag.

---

## 0. Preuve par les données (prod, 129 trades persistés)

Analyse de `GET /api/quant/simulations` (prod, 2026-06-03) — sert de justification au design :

| Instr | N | Win% | Net pts | **Net USD** | Décision |
|---|--:|--:|--:|--:|---|
| **MNQ** | 81 | 51% | +242.50 | **+$485** | ✅ exécution réelle |
| **MGC** | 28 | 26% | −30.30 | **−$303** | ❌ simulé only (perdant) |
| **MCL** | 20 | 58% | +0.57 | **+$57** | ✅ exécution réelle |
| **6E (E6)** | 0 | — | — | **aucun trade** | ❌ non scanné |
| **TOTAL** | 129 | 47% | +212.77 | **+$239** | — |

Constats structurels qui pilotent le design :
- **0 chevauchement** LONG/SHORT (ni opposé, ni même sens) sur 129 trades → le modèle « un seul sens par instrument » est déjà le comportement naturel (les tags `[ABS BULL ACTIVE]` / `[ABS BEAR ACTIVE]` sont mutuellement exclusifs).
- **Pas un système de reversal** : sens du trade suivant ≈ 50/50 (MNQ 39 flips / 41 mêmes sens), écart médian sortie→entrée ≈ 240 s (MNQ) à 2100 s (MCL). Seuls **7/129** sont des ré-entrées opposées au tick exact.
- **Cas limite same-tick** (~9 % des transitions, gap = 0 s, opposé *ou* même sens) : une nouvelle entrée peut tomber pendant qu'un exit est encore en vol (`EXIT_SUBMITTED`, pas encore fillé). → géré par un **gate de ré-entrée** (cf. §2.4), pas par un REVERSE deux-jambes.
- Sorties : **93 % en `flow AVOID`** (net ≈ scratch), l'edge vient des **7 TP1** (+$657). 6E n'est jamais scanné (`QuantGateScheduler.INSTRUMENTS = MNQ, MGC, MCL`).

---

## 1. Principe

La **simulation papier reste intacte** (stats inchangées, toujours sur MNQ/MGC/MCL). On y greffe un bridge qui **mirroir** chaque setup qualifié vers un vrai ordre IBKR, **uniquement pour les instruments de l'allowlist et dont le toggle est ON**.

Le bridge n'écrit **que** dans `trade_executions` (jamais dans `quant_7gates_simulations` ni dans les tables mentor) → la *Simulation Decoupling Rule* (`ARCHITECTURE_PRINCIPLES.md`) est respectée. C'est le **6ᵉ** `ExecutionTriggerSource` routé vers IBKR ; il **migrera vers `OrderRouter`** en Phase 2b avec les autres (cf. `ADR_UNIFIED_EXECUTION_CORE.md`).

```
QuantGateService.scan()
  └─ Quant7GatesSimulationService.onSnapshot()         (papier — inchangé)
        ├─ maybeClose() → ligne CLOSED_*  ──► bridge.submitClose()   (flatten IBKR)
        └─ tryOpen()    → ligne OPEN      ──► bridge.submitOpen()    (LMT entrée IBKR)
                                              │  (gardes: enabled, allowlist, toggle,
                                              │   no-double-active, exit-in-flight)
                                              ▼
                                    IbkrOrderService.submitEntryOrder()
                                              ▼
                                    ExecutionFillTrackingService (réconcilie par ibkrOrderId — agnostique de la source)
```

---

## 2. Backend

### 2.1 Trigger source
`domain/model/ExecutionTriggerSource.java` → ajouter `QUANT_SIM_AUTO` (+ javadoc : opt-in par instrument via toggle panel, allowlist MNQ/MCL, no mentor review, entrée Limit only).

### 2.2 Bridge dédié
- **Interface** `application/quant/simulation/Quant7GatesExecutionBridge` :
  - `RoutingResult submitOpen(Instrument, Direction, BigDecimal entryPrice, int qty, String reason, Instant ts)`
  - `RoutingResult submitClose(Instrument, Direction, BigDecimal exitPrice)`
- **Impl** `IbkrQuant7GatesExecutionBridge` (`@ConditionalOnProperty(name = "riskdesk.quant.sim-exec.enabled", havingValue = "true")`), calqué sur `IbkrWtxRsiExecutionBridge` :
  - `executionKey = quant-sim:<instr>:<dir>:<tsMillis>:OPEN`.
  - OPEN → `PENDING_ENTRY_SUBMISSION` → `IbkrOrderService.submitEntryOrder` (LMT au prix arrondi au tick `Instrument.getTickSize()`) → `ENTRY_SUBMITTED`.
  - CLOSE → localise la ligne active `QUANT_SIM_AUTO` de l'instrument (`findActiveByInstrumentAndTimeframeAndTriggerSource`, timeframe `"5m"`) → transition vers `EXIT_SUBMITTED` (**jamais** de nouvelle ligne — même contrat que WTX-RSI). Garde anti double-flatten sur `EXIT_SUBMITTED`.
  - Retour **`com.riskdesk.domain.execution.RoutingResult` / `RoutingOutcome`** (enum générique, pas `WtxRoutingOutcome`).

### 2.3 Gardes du bridge (ordre d'évaluation)
1. `riskdesk.ibkr.enabled` + master flag construit le bean.
2. **Allowlist** : instrument ∈ `riskdesk.quant.sim-exec.instruments` (`MNQ,MCL`) — sinon `SKIPPED` (garde dure : MGC/6E impossibles même si toggle forcé).
3. **Toggle instrument ON** (état runtime, défaut OFF).
4. `qty > 0`, `entryPrice != null`.
5. Dedupe par `executionKey` (`findByExecutionKey`).
6. **No-double-active** : aucune exécution vivante `QUANT_SIM_AUTO` sur l'instrument (`findActiveByInstrument`).
7. **Exit-in-flight (cas same-tick)** : si une ligne de l'instrument est en `EXIT_SUBMITTED` (close pas encore fillée), **skipper l'entrée de ce tick** (`SKIPPED_EXIT_IN_FLIGHT`) ; la sim la retentera au tick suivant une fois la ligne `CLOSED`. Évite la collision de jambes sans REVERSE deux-jambes.

### 2.4 Toggle par instrument
- `QuantSimExecutionState` (composant, map `EnumMap<Instrument,Boolean>` en mémoire, défaut OFF). Reset au restart = OFF → sûr par défaut.
- Master flag `riskdesk.quant.sim-exec.enabled` (défaut `false`).

### 2.5 Câblage dans `Quant7GatesSimulationService`
- Injecter `ObjectProvider<Quant7GatesExecutionBridge>` (même pattern que `publisherProvider` / `repositoryProvider` → tests sans bean OK).
- Dans `tryOpen(...)`, après `publish/persist` de la ligne OPEN → `bridge.submitOpen(...)` (best-effort, try/catch loggé).
- Dans `onSnapshot(...)`, quand `maybeClose` renvoie une ligne fermée → `bridge.submitClose(...)`.
- **Le papier n'est jamais altéré** par le résultat du bridge (les stats restent celles de la simulation).

### 2.6 Sortie de session — force-close 17:00 ET
`WtxNySessionCloseScheduler` est scopé WTX. **Étendre** le force-close (ou ajouter un scheduler jumeau) pour flatten aussi les positions `QUANT_SIM_AUTO` avant la clôture — filet de sécurité « no-SL » : pas de position orpheline la nuit si la sim ne ferme pas.

### 2.7 Config (`application.properties`)
```properties
riskdesk.quant.sim-exec.enabled=false
riskdesk.quant.sim-exec.instruments=MNQ,MCL
riskdesk.quant.sim-exec.broker-account-id=
riskdesk.quant.sim-exec.default-quantity=1
```
Nouveau `QuantSimExecutionProperties` (`@ConfigurationProperties("riskdesk.quant.sim-exec")`). `broker-account-id` requis quand `enabled=true` (lève `IllegalStateException` sinon, comme `QuantAutoArmService`).

### 2.8 REST (étend `Quant7GatesSimulationController`, base `/api/quant/simulations`)
- `PUT /{instrument}/auto-execution` `{ "enabled": true|false }` → refuse 400 si instrument hors allowlist.
- `GET /exec-state` → `{ masterEnabled, allowlist:[…], toggles:{MNQ:true,…} }`.

---

## 3. Frontend

`frontend/app/components/quant/Quant7GatesSimulationPanel.tsx` :
- Switch **Auto-IBKR** par instrument (OFF défaut, calqué sur le panel WTX). Visible/cliquable **uniquement pour MNQ & MCL** ; MGC affiché « simulation only » (perdant −$303), 6E non listé.
- Badge **« live #id »** sur une ligne OPEN mirrorée vers un ordre réel ; info-bulle « un seul sens par instrument ».
- `lib/api.ts` : `setQuant7GatesAutoExecution(instrument, enabled)` + `getQuant7GatesExecState()`.
- `npm run lint` obligatoire.

---

## 4. Tests

- `IbkrQuant7GatesExecutionBridgeTest` : submitOpen happy / dedupe / no-double-active / **hors-allowlist** / toggle-OFF / ibkr-OFF / **exit-in-flight skip** ; submitClose flatten / double-flatten dedupe / no-open-row skip.
- `Quant7GatesSimulationServiceTest` (étendu) : `RecordingQuant7GatesBridge` invoqué sur open **et** close ; **non** invoqué quand le provider est vide ; papier inchangé quel que soit le retour du bridge.
- Réconciliation : un fill sur une ligne `QUANT_SIM_AUTO` passe `ENTRY_SUBMITTED → ACTIVE` puis `EXIT_SUBMITTED → CLOSED` via `ExecutionFillTrackingService` (déjà agnostique de la source — `findByIbkrOrderId`).
- Force-close : une position `QUANT_SIM_AUTO` ouverte est flattenée par le scheduler 17:00 ET.

---

## 5. Docs à mettre à jour
- Javadoc `ExecutionTriggerSource` (nouvelle valeur).
- `docs/AI_HANDOFF.md` (changement + raison), `docs/PROJECT_CONTEXT.md` (6ᵉ source routée, allowlist).
- `docs/PLAN_AUTO_IBKR_EXECUTION.md` : ajouter `QUANT_SIM_AUTO` à la liste des sources, à migrer vers `OrderRouter` en Phase 2b.

---

## 6. Découpage en PR (depuis `main`, préfixe `claude/`, 1 PR par tâche)

| PR | Contenu | Validation |
|---|---|---|
| **PR1** | Trigger source + bridge (interface+impl) + toggle state + `QuantSimExecutionProperties` + câblage sim + config + tests bridge/sim | `mvn -q -Dtest=IbkrQuant7GatesExecutionBridgeTest,Quant7GatesSimulationServiceTest test` + `mvn -q -DskipTests compile` |
| **PR2** | REST endpoints + force-close 17:00 ET étendu + tests contrôleur/scheduler | `mvn -q -Dtest=… test` |
| **PR3** | Frontend toggle + `api.ts` + badge | `cd frontend && npm run lint && npm run build` |
| **PR4** | Docs (`AI_HANDOFF`, `PROJECT_CONTEXT`, `PLAN_AUTO_IBKR_EXECUTION`) | relecture |

**Séquence :** PR1 → PR2 → PR3 → PR4. Auto-IBKR reste **OFF** tant que le master flag + les toggles MNQ/MCL ne sont pas activés explicitement en prod.

---

## 7. Carte des fichiers clés

| Rôle | Fichier |
|---|---|
| Harnais simulation (à câbler) | `application/quant/simulation/Quant7GatesSimulationService.java` |
| **Bridge (nouveau)** | `application/quant/simulation/Quant7GatesExecutionBridge.java` (+ `IbkrQuant7GatesExecutionBridge.java`) |
| Modèle de référence | `application/service/strategy/wtxrsi/IbkrWtxRsiExecutionBridge.java` |
| Soumission ordre | `application/service/IbkrOrderService.java` |
| Résultat de routage générique | `domain/execution/RoutingResult.java`, `RoutingOutcome.java` |
| Repo exécutions | `domain/execution/port/TradeExecutionRepositoryPort.java` |
| Réconciliation fills | `application/service/ExecutionFillTrackingService.java` |
| Force-close NY (à étendre) | `application/service/strategy/WtxNySessionCloseScheduler.java` |
| Trigger sources | `domain/model/ExecutionTriggerSource.java` |
| Scheduler scan (scope instruments) | `application/quant/scheduling/QuantGateScheduler.java` |
| Controller sim | `presentation/quant/Quant7GatesSimulationController.java` |
| Panel | `frontend/app/components/quant/Quant7GatesSimulationPanel.tsx` |
| Config | `src/main/resources/application.properties` |
