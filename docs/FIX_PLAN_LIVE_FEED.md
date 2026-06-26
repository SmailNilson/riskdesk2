# Plan de correction définitif — « les données live meurent après quelques heures, seul le redéploiement répare »

> Plan vérifié par revue adverse (4 claims, dont 3 réfutées). Établi le 2026-06-26.

## 1. Diagnostic corrigé

L'hypothèse initiale « le `@Scheduled(fixedDelay)` qui jette une exception est annulé pour toujours » est **un mythe pour cette configuration** : vérifié sur Spring 6.1.5 (Boot 3.2.4), `TaskSchedulingConfig` ne pose **aucun** `errorHandler`, donc `scheduleWithFixedDelay` enveloppe la tâche avec `LOG_AND_SUPPRESS_ERROR_HANDLER` (`TaskUtils.getDefaultErrorHandler(true)`) — l'exception est **loggée puis avalée**, la `Future` périodique n'est jamais annulée, et `pollPrices()` continue de tourner toutes les 30 s. Le mythe n'est vrai que pour une tâche one-shot ou un handler propageant — ni l'un ni l'autre ici. La **cause primaire réelle** est la mort silencieuse du socket IBKR : `EClientSocket.isConnected()` reste `true` (le `m_socket.isConnected()` JDK ne retombe jamais sur une coupure half-open, et `EReader` peut avaler une exception non-EOF sans appeler `eDisconnect()`), donc `ensureConnected()` court-circuite à la l.548, `disconnected()` ne se déclenche jamais, et `latestStreamingPrice()` sert un prix gelé indéfiniment sans jamais basculer en `FALLBACK_DB`. **Cause aggravante** : `connected()` (l.1869) ne rappelle jamais `resubscribeAll()` (l.1494) — confirmé : `resubscribeAll()` n'est appelé **nulle part** dans `src/main` — donc même une reconnexion propre revient sans abonnements prix. **Causes secondaires/défense** : `pollPrices()` sans isolation par instrument (un throw avale le reste du cycle + la queue DXY/VIX) et le front qui n'affiche que l'état du handshake STOMP, pas la fraîcheur des données. **Déclencheur externe** : l'auto-logoff quotidien d'IB Gateway. La priorité est donc : **(P0)** watchdog de fraîcheur prix + `resubscribeAll()` dans `connected()` + détection de socket-zombie ; **(P1)** isolation `pollPrices` + observabilité ; **(P2)** UX front + ops IB Gateway. Le « scheduler errorHandler » reste utile **uniquement** pour l'observabilité, pas pour empêcher une annulation qui n'existe pas.

---

## 2. Plan de correction

### P0 — Récupération automatique du flux prix (le cœur)

> **RÈGLE D'ARCHITECTURE : un seul propriétaire de la reconnexion.** On introduit `IbGatewayNativeClient.forceReconnect()` qui fait `disconnect()` + `ensureConnected()` + (implicite via `connected()`) `resubscribeAll()`. Le watchdog `MarketDataService` ET `OrderFlowOrchestrator.checkConnectionHealth()` **appellent ce seul point d'entrée** — ils ne déclenchent jamais de teardown TCP indépendant. La fusion des specs « checkConnectionHealth upgrade » et « nouveau watchdog » se résout ainsi : le watchdog `MarketDataService` (cadence 30 s) est le détecteur primaire ; `checkConnectionHealth` (300 s) est le backstop lent ; les deux passent par `forceReconnect()`.

**P0.1 — `connected()` restaure les abonnements (OFF-thread obligatoire)**
- Fichier : `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayNativeClient.java`
- Ancre : fin de `ConnectionHandler.connected()` (l.1869-1899), après `attachFillTrackingHandlersIfNeeded`.
- Change : dispatcher `resubscribeAll()` sur `CLEANUP_EXECUTOR` (l.83) — **jamais inline**.
```java
// fin de connected(), après attachFillTrackingHandlersIfNeeded(controller)
CLEANUP_EXECUTOR.submit(() -> {
    try { resubscribeAll(); }
    catch (Exception e) { log.warn("Post-connect resubscribeAll failed: {}", e.getMessage()); }
});
```
- Risque/garde-fou : `connected()` tourne sur l'unique thread de message tws-api. Un appel **inline** déclencherait `ensureConnected().get(10s)` sur une `accountsFuture` que seul ce même thread peut compléter → **self-deadlock 10 s puis teardown de la connexion fraîche** (verdict CONFIRMÉ). `CLEANUP_EXECUTOR` (single-thread daemon, déjà utilisé par `submitCleanup` l.1651) lève ce piège : sur le worker, `accountList()` peut compléter la future côté message-thread.

**P0.2 — `forceReconnect()` : propriétaire unique de la reconnexion**
- Fichier : `IbGatewayNativeClient.java`
- Ancre : nouvelle méthode publique près de `disconnect()` (l.585).
- Change :
```java
public boolean forceReconnect(String reason) {
    log.error("forceReconnect: {} — tearing down (isConnected reported {})", reason, isConnected());
    disconnect();                 // l.585 : clearStateLocked + reconnectBlockedUntil=EPOCH
    boolean up = ensureConnected();
    // resubscribeAll() est rappelé par connected() (P0.1) sur le nouveau socket
    return up;
}
```
- Risque/garde-fou : `disconnect()` met `reconnectBlockedUntil=EPOCH` (l.589) → le `ensureConnected()` suivant n'est **pas** throttlé (voulu). Mais ça **désarme aussi le RECONNECT_COOLDOWN 5 s** → le rate-limiting doit vivre côté appelant (cf. P0.3 cooldown dédié + compteur de strikes).

**P0.3 — Watchdog de fraîcheur prix dans `MarketDataService`**
- Fichier : `src/main/java/com/riskdesk/application/service/MarketDataService.java`
- Ancres : bloc champs ~l.77-93 ; `onLivePriceUpdate` (l.197) ; nouveau `@Scheduled` après `pollPrices` (l.190) ; constructeur (ajout `ObjectProvider<IbGatewayNativeClient>`).
- Change : enregistrer la liveness à l'arrivée du callback (avant tout debounce), puis un watchdog qui force la reconnexion via `forceReconnect()` quand le marché est ouvert, hors fenêtre de maintenance, socket « connecté » mais **tous** les abonnements silencieux.
```java
private volatile Instant lastLiveTickAt = null;
private volatile Instant lastForcedReconnectAt = Instant.EPOCH;

@Override public void onLivePriceUpdate(Instrument i, BigDecimal p, Instant ts){
  lastLiveTickAt = Instant.now();   // liveness = arrivée du callback, AVANT tout return debounce/samePrice
  /* ...corps existant inchangé... */
}

@Scheduled(fixedDelayString="${riskdesk.market-data.price-watchdog.check-interval-ms:30000}",
           initialDelayString="${riskdesk.market-data.price-watchdog.initial-delay-ms:180000}")
public void priceFeedFreshnessWatchdog(){
  if (nativeClient == null || !priceWatchdogEnabled) return;
  Instant now = Instant.now();
  if (!TradingSessionResolver.isMarketOpen(now)) return;
  if (TradingSessionResolver.isStandardMaintenanceWindow(now)
      || TradingSessionResolver.isFxMaintenanceWindow(now)) return;   // 17:00-18:00 ET + 6E 16:00-17:00 ET
  if (!nativeClient.isConnected()) return;                            // flux down → pollPrices DB-fallback gère
  Instant last = lastLiveTickAt;
  if (last == null) return;                                          // jamais réchauffé → ne pas churner
  long ageSec = Duration.between(last, now).getSeconds();
  if (ageSec <= stalenessThresholdSeconds) return;                   // défaut 120s
  if (Duration.between(lastForcedReconnectAt, now).getSeconds() < forcedReconnectCooldownSeconds){
    log.warn("Price feed stale {}s but isConnected()=true — reconnect throttled", ageSec); return;
  }
  lastForcedReconnectAt = now;
  nativeClient.forceReconnect("silent price-feed death: no tick " + ageSec + "s");
}
```
- Constructeur : `ObjectProvider<IbGatewayNativeClient>` → `getIfAvailable()` (null sous profils non-`IB_GATEWAY`, pas de crash de contexte).
- Config (`application.properties`) : `riskdesk.market-data.price-watchdog.{enabled=true,check-interval-ms=30000,initial-delay-ms=180000,staleness-seconds=120,cooldown-seconds=120}`.
- Risque/garde-fou : seuil **120 s** (plancher agressif 90 s) — `90 s` mal-tire pendant le creux nocturne 6E/MCL hors maintenance ; **gate sur `isMarketOpen` SEUL est insuffisant** car `isMarketOpen` renvoie `true` pendant le halt 17:00-18:00 ET (Javadoc l.151) → `isStandardMaintenanceWindow`/`isFxMaintenanceWindow` **obligatoires**. `volatile Instant` (une écriture callback-thread, une lecture scheduler-thread) → pas de lock. `forceReconnect` jamais appelé en tenant un moniteur `MarketDataService`.

**P0.4 — Détection du socket-zombie dans `ensureConnected()` + backstop 300 s**
- Fichier : `IbGatewayNativeClient.java`
- Ancres : nouveau helper `isStreamingPriceFeedStale()` ; `OrderFlowOrchestrator.checkConnectionHealth()` (l.1537).
- Change : exposer un prédicat `isStreamingPriceFeedStale()` (lecture lock-free d'un snapshot de `streamingSubscriptions.values()`, `lastPriceAt` volatile, gate `isMarketOpen` + maintenance + warmup) et l'utiliser dans `checkConnectionHealth` comme backstop — qui appelle `forceReconnect()` au lieu de seulement logger.
```java
// OrderFlowOrchestrator.checkConnectionHealth() — corrige le javadoc menteur l.1530-1534
if (nativeClient.isConnected() && nativeClient.isStreamingPriceFeedStale()) {
    log.warn("Health check: price feed stale while socket connected — forcing reconnect+resubscribe");
    nativeClient.forceReconnect("health-check: stale price feed");   // connected() resubscribe (P0.1)
}
// ...logique tick-by-tick / éviction par instrument inchangée...
```
- Risque/garde-fou : ne **pas** appeler `resubscribeAll()` directement sur un socket stale-mais-`isConnected()`-true (on re-souscrirait sur un socket mort) — toujours passer par `forceReconnect()`. `checkConnectionHealth` reste le backstop lent (300 s) ; le détecteur rapide est P0.3 (~30 s). Ne pas dupliquer le teardown : un seul propriétaire = `forceReconnect()`.

---

### P1 — Résilience & observabilité backend

**P1.1 — Isolation par instrument dans `pollPrices()`**
- Fichier : `src/main/java/com/riskdesk/application/service/MarketDataService.java`
- Ancre : corps `pollPrices()` (l.117-190) — boucle par instrument + queue dxy/vix (l.176-181).
- Change : `try/catch (Exception)` par itération + `try/catch` séparé pour `refreshSyntheticDxy()` et le publish VIX, pour qu'un throw (DB write l.154, `publishEvent` synchrone l.156 → `QuantSimFastExitListener`/`CrossInstrumentAlertService`, ou `candlePort.save`) ne saute ni les instruments suivants ni la queue.
```java
for (Instrument instrument : Instrument.exchangeTradedFutures()) {
    try { /* corps existant ; usedDatabaseFallback set DANS le try */ }
    catch (Exception e) { log.warn("pollPrices: skipping {} this cycle: {}", instrument, e.toString(), e); }
}
try { dxyMarketService.refreshSyntheticDxy(); } catch (Exception e) { log.warn("DXY refresh failed: {}", e.toString(), e); }
try { marketDataProvider.fetchVixPrice().ifPresent(v -> eventPublisher.publishEvent(new MarketPriceUpdated("VIX", v, now))); }
catch (Exception e) { log.warn("VIX publish failed: {}", e.toString(), e); }
// bloc transition databaseFallbackActive (l.183-189) reste DERNIER, non-wrappé
```
- Risque/garde-fou : `catch Exception` (pas `Throwable`) ; ne **pas** mettre à jour `lastPrice/lastTimestamp/lastSource` pour un instrument en échec (cache stale-mais-cohérent > demi-mis-à-jour) ; `usedDatabaseFallback` set dans le `try`, jamais reset par un catch ; `alertService`/`behaviourAlertService` (l.165-172) déjà `runAsync` isolés — ne pas les re-wrapper.

**P1.2 — `errorHandler` scheduler (observabilité seulement)**
- Fichier : `src/main/java/com/riskdesk/infrastructure/config/TaskSchedulingConfig.java`
- Ancre : `configureTasks()`, après `scheduler.setAwaitTerminationSeconds(5)`, **avant** `scheduler.initialize()` (l.52).
- Change :
```java
scheduler.setErrorHandler(t -> {
    log.error("Scheduled task threw (suppressed, will continue): {}", t.toString(), t);
    schedulerHealth.recordFailure(t);   // compteur/event non-bloquant
});
scheduler.initialize();
```
- Risque/garde-fou : **ne JAMAIS rethrow** depuis `handleError` — rethrow convertit suppress→propagate et annule réellement la tâche fixedDelay (le seul vrai mode d'annulation). Set **avant** `initialize()` (lu une fois au schedule). Handler cheap (pas d'I/O broker/DB, tourne sur `riskdesk-sched-`). Rate-limit le log (un ERROR par transition) pour éviter le spam 30 s.

**P1.3 — Surface santé / métrique (optionnel, faible coût)**
- Fichiers : `src/main/java/com/riskdesk/infrastructure/health/MarketDataFeedHealthIndicator.java` (nouveau, bean `marketDataFeed`) ; gauge Micrometer `riskdesk.marketdata.last_live_tick_age_seconds` ; alarme Telegram via `NotificationPort.sendMarketDataFeedStale(...)` (default no-op, domaine framework-free).
- Change : ajouter `lastLiveTimestamp` map (stampée **uniquement** sur `LIVE_PROVIDER`/`LIVE_PUSH`) + accessors read-only ; `HealthIndicator` UP/DOWN gaté par `isMarketOpen`+maintenance ; moniteur hystérésis `volatile boolean feedStale` → alarme exactement une fois par transition.
- Risque/garde-fou : un DOWN flippe `/actuator/health` en 503 → **risque de bloquer le health-check de déploiement** (480 s, cf. note `deploy-healthcheck-boot-failure`). Mettre `marketDataFeed` dans un **groupe actuator dédié non-liveness** (`management.endpoint.health.group.feed.include=marketDataFeed`) ou informational-only. `HexagonalArchitectureTest` : health/monitor en `infrastructure/`, event en `domain/` framework-free. (Actuator EST déjà au classpath — `pom.xml:135` ; expo `health,info,metrics,env` à `application.properties:187-188`.)

---

### P2 — UX front + ops IB Gateway

**P2.1 — Fraîcheur côté front (banner « données figées »)**
- Fichiers : `frontend/app/hooks/useWebSocket.ts` (état l.35-47, handler `/topic/prices` l.70-81, return l.142-146) ; `frontend/app/components/cockpit/VitalHeader.tsx` ; `frontend/app/components/Dashboard.tsx` ; `frontend/app/components/mobile/OfflineBanner.tsx`.
- Change : `lastPriceAtRef`/`priceAgeMs`, tick 1 s, `dataStale = connected && priceAgeMs > STALE_THRESHOLD_MS` (45 000 ms pour franchir le poll 30 s). Feed à 3 états dans `VitalHeader` (OFF rouge / FIGÉ ambre / LIVE vert). Banner desktop + mobile « Données figées depuis Xs — reconnexion… ».
```ts
const STALE_THRESHOLD_MS = 45_000;
const lastPriceAtRef = useRef(0);
const [priceAgeMs, setPriceAgeMs] = useState<number | null>(null);
// 1ère ligne du cb /topic/prices : lastPriceAtRef.current = Date.now();
// setInterval 1s : setPriceAgeMs(lastPriceAtRef.current===0?null:Date.now()-lastPriceAtRef.current)
const dataStale = connected && priceAgeMs != null && priceAgeMs > STALE_THRESHOLD_MS;
```
- Risque/garde-fou : garder `connected` (distinguer socket-down vs feed gelé). Gater le banner sur marché ouvert / `source==='FALLBACK_DB'` vs `'STALE'` (fermé) pour ne pas afficher « reconnexion… » la nuit/week-end. **Display-only** pour ce slice (pas d'auto-reconnect client : inutile tant que le backend ne re-souscrit pas — c'est P0).

**P2.2 — Timeout + retry borné dans `api.ts` / pollers Dashboard / PlaybookPanel**
- Fichiers : `frontend/app/lib/api.ts` (`get<T>` l.270), `Dashboard.tsx` (`loadSummary`/`loadSnapshot`, catches vides), `PlaybookPanel.tsx`.
- Change : `AbortController` timeout ~8 s + 2 retries backoff (300/900 ms) sur les **lectures** uniquement ; `post/put/del` timeout-only **sans retry** (un POST rejoué double-soumettrait manual-trade/executions). Remplacer les `catch {}` par `console.warn` + flag stale + un seul fast-retry 2 s. `PlaybookPanel` garde le dernier état valide au lieu de blanker.
- Risque/garde-fou : préserver le contrat `readErrorSuffix` (l.314-329). Ne **jamais** retry les écritures. Annuler les timers de retry au changement instrument/timeframe.

**P2.3 — Ops IB Gateway (le déclencheur externe « après quelques heures »)**
- Pas un changement de code : configurer IBC `autoRestartTime` (ex. `11:45 PM` local) ou GUI *Configure > Settings > Lock and Exit* → **« Auto restart »** (pas « Auto logoff ») + **« Never lock »**. Sous Docker (`UnusualAlpha/ib-gateway-docker`) : env `autoRestartTime` ou `ClosedownAt` + `--restart unless-stopped`.
- Vérif : après l'heure de restart, `GET /api/ibkr/auth/status` → connecté `socket://ibkr-gateway:4003`, `/topic/prices` reprend, logs montrent `IB Gateway native API connected` **suivi de** `resubscribeAll: restoring N subscriptions` (cette 2e ligne ne paraît qu'avec P0.1).

---

## 3. Garde-fous & pièges

- **Thread de message tws-api = unique.** `connected()`/`accountList()` y sont sérialisés. `resubscribeAll()` inline → `ensureConnected().get(10s)` attend une `accountsFuture` que seul ce thread peut compléter → self-deadlock 10 s + teardown. **Toujours OFF-thread via `CLEANUP_EXECUTOR`.**
- **`isConnected()` ment.** `m_socket.isConnected() && m_connected` reste `true` sur mort half-open (contrat JDK + `EReader` sans `setSoTimeout` + chemin non-EOF avalé sans `eDisconnect()`). **Un check de connexion ne suffit pas — la récupération doit être pilotée par la FRAÎCHEUR des données.** (CONFIRMÉ par décompilation du jar tws-api 10.39.1.)
- **Un seul propriétaire de la reconnexion = `forceReconnect()`.** Le watchdog prix ne fait jamais de teardown TCP indépendant ; le backstop 300 s passe par le même point. Évite la guerre avec les watchdogs tick.
- **`disconnect()` désarme le cooldown 5 s** (`reconnectBlockedUntil=EPOCH`) → rate-limiting via un **cooldown dédié** (`forcedReconnectCooldownSeconds`, défaut 120 s) + compteur de strikes (backoff après K=3 échecs, reset au premier tick frais).
- **Session-break = faux positif #1.** `isMarketOpen` renvoie `true` pendant 17:00-18:00 ET (et 6E 16:00-17:00 ET). **Gate impératif sur `isStandardMaintenanceWindow`+`isFxMaintenanceWindow`** + grâce de warmup ~120 s après réouverture.
- **Seuil staleness 120 s** (plancher 90 s) : > pire creux légitime hors maintenance. `initialDelay` watchdog 180 s > `initialDelay` poll 60 s.
- **`errorHandler` scheduler : ne jamais rethrow** (rethrow = la seule vraie annulation). Observabilité, pas « anti-annulation ».
- **DXY** : `resubscribeAll()` ne couvre que les futures `isExchangeTradedFuture()` ; les legs FX DXY se ré-arment via `DxyMarketService` — vérifier qu'ils reviennent post-reconnect.

---

## 4. Validation

**Backend — tests ciblés**
- `MarketDataServiceTest` : `pollPrices_throwingDependencyOnOneInstrument_doesNotAbortPoll` (mock `positionService.updateMarketPrice(MCL)` jette → MGC encore mis à jour + `refreshSyntheticDxy()` appelé + `pollPrices()` ne jette pas) ; isolation des listeners synchrones.
- `MarketDataServicePriceWatchdogTest` : (a) stale+open+connected → `forceReconnect()` 1×, (b) frais → aucune interaction, (c) week-end → no-op, (d) maintenance standard 17:30 ET → no-op, (e) maintenance FX 16:30 ET → no-op, (f) 2 passes < cooldown → 1 seul `forceReconnect`, (g) `nativeClient==null` → no-op sans NPE, (h) `lastLiveTickAt` stampé même sur samePrice/debounce.
- `IbGatewayNativeClientReconnectResubscribeTest` : `resubscribeAll()` avec registry/resolver/controller (`client.isConnected()=true`) → `reqTopMktData` émis pour l'entrée PRICE ; guard registry/resolver null → no-op, controller intouché.
- `IbGatewayNativeClient` lock/deadlock guard : `connected()` (dispatch `CLEANUP_EXECUTOR`) concurrent avec `disconnect()`/`clearStateLocked()` → pas de deadlock dans un timeout, état final cohérent.
- `OrderFlowOrchestratorPriceFreshnessTest` : `isConnected()=true`+stale>60s+open → `forceReconnect()` vérifié ; dans la grâce / marché fermé → pas de reconnexion.
- Bornes temporelles (règles CLAUDE.md) : suppression de la reconnexion à la coupure 17:00 ET, week-end, DST printemps/automne.
- Régression : test/grep que `TaskSchedulingConfig` pose un `errorHandler` ; `HexagonalArchitectureTest` vert.

**Frontend** : check manuel — backend up → `VitalHeader` LIVE vert ; bloquer `/topic/prices` STOMP up → sous ~45 s dot ambre « FIGÉ Xs » + banner ; couper le socket → dot rouge OFF distinct ; black-hole host → `get<T>` abort ~8 s ; 503 → 2 retries puis message ; 404/400 → pas de retry ; `post/put/del` → pas de retry.

**Check IB-Gateway-kill** : `docker stop ibkr-gateway` (prod) ou bloquer port 4003 local → `pollPrices` sert `FALLBACK_DB`, le front fige, banner apparaît ~90 s alors que STOMP reste `connected` ; redémarrer → logs `IB Gateway native API connected` **+** `resubscribeAll: restoring N subscriptions` → `/topic/prices` reprend **sans redéploiement**.

**Commandes**
```bash
mvn -q -DskipTests compile
mvn -q -Dtest=MarketDataServiceTest test
mvn -q -Dtest=MarketDataServicePriceWatchdogTest test
mvn -q -Dtest=IbGatewayNativeClientReconnectResubscribeTest test
mvn -q -Dtest=OrderFlowOrchestratorPriceFreshnessTest test
mvn -q -Dtest=HexagonalArchitectureTest test
cd frontend && npm run lint
```

---

## 5. Découpage PR proposé

1. **PR 1 — Self-heal backend (P0)** : `connected()`→`resubscribeAll()` off-thread, `forceReconnect()`, `isStreamingPriceFeedStale()`, watchdog `MarketDataService`, upgrade `checkConnectionHealth` (un seul propriétaire). + tous les tests reconnect/resubscribe/watchdog/freshness. **C'est la PR qui ferme le bug — à livrer en premier.**
2. **PR 2 — Résilience & observabilité backend (P1)** : isolation `pollPrices`, `errorHandler` scheduler, `HealthIndicator`/gauge/alarme Telegram. Indépendante de PR 1, peut suivre immédiatement.
3. **PR 3 — UX front + ops (P2)** : `useWebSocket` freshness, `VitalHeader`/banners, timeouts+retry `api.ts`, retry `PlaybookPanel`. + note ops IB Gateway (IBC `autoRestartTime`). Vérifier les PR front ouvertes (conflits Codex/MAQ).

Ordre : **PR 1 → PR 2 → PR 3**. PR 1 et PR 2 touchent toutes deux `MarketDataService` (watchdog vs isolation) → si concurrentes, livrer PR 1 d'abord et rebaser PR 2.

---

## 6. Risque résiduel

- **Pas de brackets OCO réels.** `forceReconnect()` mid-session tear-down les souscriptions ordre/compte ; `connected()` ré-attache les fill handlers et le compte se ré-amorce paresseusement — bref trou pour l'état exécution WTX/Playbook (les SL/TP Playbook restent virtuels app-side).
- **TOCTOU pré-existant** : `controller` nullé entre le check `isConnected()` et `reqTopMktData` dans `ensureStreamingPriceSubscription` (l.1281) — non aggravé, non corrigé ici.
- **Creux nocturne légitime** : à 120 s, un instrument réellement vivant mais très calme hors maintenance pourrait théoriquement déclencher une reconnexion ; nécessite **une fenêtre d'observation prod** pour confirmer zéro faux positif (abaisser à 90 s seulement après).
- **Tick-by-tick / order-flow** : `resubscribeAll()` couvre PRICE/QUOTE/DEPTH ; le tick-by-tick (socket séparé) revient via les schedulers `OrderFlowOrchestrator`, pas via ce fix — bref trou order-flow après un `forceReconnect`.
- **Basis aux seams de roll** : inchangé (le store n'est pas back-adjusté). Hors scope.
- **Front timeout partiel** : les call-sites bare-`fetch` (`getPlaybookAutomation`, `marketable-settings`…) qui contournent `get<T>` ne gagnent pas le timeout — follow-up.
