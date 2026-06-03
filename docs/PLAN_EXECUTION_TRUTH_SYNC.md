# Plan — Fiabiliser l'auto-trade « une fois pour toutes » : la vérité broker fait loi

> **Statut :** plan de fond · **Date :** 2026-06-03 · **Priorité : CRITIQUE (perte réelle ~1000$ sur 2 jours).**
>
> **Constat honnête.** Les correctifs récents (réconciliateurs `StaleCloseReconciler`, #396–399) sont des
> **pansements** : ils nettoient les lignes coincées *après coup*. La cause profonde n'est pas réglée, donc
> de nouvelles divergences réapparaissent. Ce plan attaque la **racine**.

---

## Principe directeur (le seul qui compte)

> **La vérité broker fait loi.** L'app ne doit JAMAIS :
> 1. router un ordre sur un état qu'elle n'a pas réconcilié contre le broker, ni
> 2. maintenir un état (ligne d'exécution **ou** état virtuel de stratégie) qui contredit ce qu'IBKR détient réellement.
>
> Tout le reste découle de ça. Aujourd'hui l'app **se fait confiance à elle-même** (ses lignes, son état
> optimiste) au lieu de se plier à IBKR → d'où les positions fantômes, les sorties ratées, et la stratégie gelée.

---

## Ce qui a réellement coûté de l'argent (causes racines)

| # | Cause | Conséquence money |
|---|---|---|
| R1 | **Réconciliation clée sur `orderId`** (réutilisé après reconnexion : plusieurs lignes = « order 21/29/41 ») | `findByIbkrOrderId` ambigu → mauvaise ligne réconciliée → fantômes |
| R2 | **Callbacks de fill perdus** (close marketable fillé *pendant* `submitEntryOrder`, avant que l'`orderId` soit persisté) | sorties jamais marquées `CLOSED` → positions fantômes, blocage |
| R3 | **État virtuel optimiste** (WTX book la position au signal, pas au fill broker) | système « LONG » alors qu'IBKR est à plat → `NONE`/`ENTRY IN FLIGHT` → ne trade plus / rate des sorties |
| R4 | **Pas de gate `RECONCILING` au boot** | un signal route avant la synchro broker → double/mauvais ordre |
| R5 | **Entrées zombies `PendingSubmit`** jamais transmises à la bourse | gèlent la stratégie (`SKIPPED_ENTRY_IN_FLIGHT`) |
| R6 | **5 chemins d'exécution fragmentés** (WTX, WTX-RSI, Playbook, Quant-sim, Quant-auto-arm) | chaque bug se duplique × 5 ; aucune réconciliation commune |
| R7 | **Aucune alarme de divergence** | on découvre le problème **en perdant de l'argent**, pas avant |

---

## Plan par priorité (du « stopper l'hémorragie » au « plus jamais »)

### P0 — Stopper l'hémorragie (immédiat, aujourd'hui)
- **Auto-IBKR OFF partout** tant que P1–P2 ne sont pas livrés (master flags + toggles WTX/quant-sim). Signaux + simulation continuent (zéro risque money). ✅ *(tu l'as déjà coupé)*
- **Alarme + auto-disable sur divergence** : un garde qui compare en continu l'état app à la vérité broker ; si ça diverge (position fantôme, état ≠ IBKR) > X s → **Telegram** + **désactive automatiquement le routage**. Mieux vaut s'arrêter que mal trader.
- Garder les réconciliateurs #396–399 comme **filet**, pas comme cure.

### P1 — Réconciliation par vérité broker (le keystone)
1. **`permId`-keyed reconciliation** : capter le `permId` (durable, jamais réutilisé) dans les callbacks `orderStatus`/`openOrder`, le persister sur `trade_executions`, et réconcilier **par `permId`** au lieu de l'`orderId` volatil. → tue R1.
2. **`BrokerTruthReconciler` unique et autoritaire** (remplace les réconciliateurs ad hoc) : périodiquement **et** au boot, force l'état app à **matcher** IBKR :
   - IBKR à plat sur un instrument ⇒ **toutes** les lignes non-terminales de cet instrument → terminales, et **tous** les états virtuels de stratégie → FLAT.
   - IBKR détient une position ⇒ exactement **une** ligne `ACTIVE` la reflète (sinon corrige).
   - Ordres : tout `ENTRY_SUBMITTED`/`EXIT_SUBMITTED` confronté aux open/completed orders **par `permId`** ; rien de « vivant » au broker ⇒ purge.
   - Garde tri-state `FOUND/NOT_FOUND/UNAVAILABLE` (jamais agir sur outage).
3. **Gate `RECONCILING` au boot** : aucune stratégie ne route tant que (open orders + positions + executions) ne sont pas synchronisés. → tue R4.

### P2 — Supprimer la divergence à la source
1. **Fill synchrone** : quand `submitEntryOrder` revient déjà `Filled`, marquer la ligne terminale **immédiatement** (ne pas dépendre d'un callback qui peut être perdu). → tue R2.
2. **État virtuel piloté par la vérité** : la position virtuelle de chaque stratégie est **dérivée du broker** (ou réconciliée à chaque barre contre la position réelle), pas bookée optimistiquement. → tue R3 et R5 (un `PendingSubmit` jamais transmis n'existe pas pour le broker → l'état reste FLAT).

### P3 — Unifier les 5 chemins (tuer la fragmentation)
- Migrer WTX, WTX-RSI, Playbook, Quant-sim, Quant-auto-arm sur **un seul `OrderRouter`** (cf. [ADR_UNIFIED_EXECUTION_CORE.md](ADR_UNIFIED_EXECUTION_CORE.md) / [PLAN_AUTO_IBKR_EXECUTION.md](PLAN_AUTO_IBKR_EXECUTION.md)). Une seule réconciliation, une seule idempotence, un seul endroit où corriger un bug. → tue R6.

### P4 — Observabilité + filets (pour ne plus jamais « découvrir en perdant »)
- **Journal d'exécution structuré** : 1 événement par tentative (`intent → submit → ack → fill → reconcile`) avec code d'erreur, requêtable.
- **Cap de perte journalière basé sur la vérité broker** (P&L réalisé IBKR, pas l'optimiste), avec coupure auto.
- **Tests « chaos »** obligatoires (le critère de « fini ») : reconnexion avec `orderId` réattribué, callback de fill perdu, fill synchrone, `PendingSubmit` jamais transmis, double signal au boot, position fermée hors-app. → dans **chaque** cas, l'état app **converge** vers la vérité broker : zéro fantôme, zéro stratégie gelée, zéro sortie ratée.

---

## Critère de « réglé une fois pour toutes »
On considère le bug clos **uniquement** quand la suite de tests chaos (P4) passe : quel que soit le désordre (déco, callbacks perdus, orderId réutilisés, fills synchrones), **l'app finit toujours alignée sur IBKR**, et une divergence résiduelle **coupe le trading + alerte** au lieu de mal trader.

## Séquence recommandée
**P0 (aujourd'hui) → P1 (le keystone : permId + BrokerTruthReconciler + gate boot) → P2 → P3 → P4.**
P0+P1 suffisent à rendre l'auto-trade **sûr à rallumer** ; P2–P4 le rendent propre et définitif.

---

## Annexe — état actuel (ce qui est déjà là)
- `DefaultOrderRouter` / `TradeIntent` / `RoutingResult` existent (flag `riskdesk.execution.unified-router`, OFF) — base de P3.
- `ExecutionReadinessGate` / `StartupReconciliationGate` existent — base du gate boot P1.3.
- `IbkrPortfolioService` (positions) + `IbkrOrderService.findOrder` (tri-state) — briques de la vérité broker.
- Réconciliateurs `StaleCloseReconciler` (#396–399) + `WtxStaleEntryReconciler` — à **remplacer** par le `BrokerTruthReconciler` unique de P1.2.
- `permId` est reçu dans le callback `orderStatus` **mais jeté** aujourd'hui (à persister — P1.1).
