# AI Handoff

Last updated: 2026-03-26

## Goal of this file

This file captures the current engineering state so another agent can continue safely without rediscovering critical decisions.

## Important Decisions Already Made

### Architecture policy

The project should continue to respect:

- DDD
- TDD
- BDD
- Hexagonal Architecture

Agents should preserve the intended layer split:

- `presentation`
- `application`
- `domain`
- `infrastructure`

Do not collapse these boundaries for convenience.

### Market data policy

External providers were intentionally removed from the active data path.

The only accepted data path is:

`IBKR Gateway -> PostgreSQL -> internal services`

Do not reintroduce Yahoo, Stooq, Alpha Vantage, Polygon, Binance, scraping, or simulated providers as production fallback.

### Mentor intermarket behavior

The mentor intermarket service was changed to avoid external HTTP lookups.

Current behavior when internal data is not available:

- correlation values may be `null`
- convergence status can be `"UNAVAILABLE"`

This is expected until internal IBKR/DB-backed inputs exist.

## Recent Technical Changes

### 1. Native IBKR client stabilization

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayNativeClient.java`

What changed:

- reconnect cooldown added
- failed connect attempts no longer thrash reconnects
- controller state is cleared on disconnect/error
- connect errors `326`, `502`, and `504` now trigger a cleaner failure path

Why:

- IB Gateway logs showed repeated `client id already in use` and connection churn

### 2. Direct preconfigured futures contract resolution

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayContractResolver.java`

What changed:

- for known futures instruments, the resolver can use a preconfigured IBKR contract directly via `conid`
- this reduces dependency on `reqContractDetails` on the live-path critical section

### 3. Live provider path simplified

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayMarketDataProvider.java`

What changed:

- live snapshot lookup no longer retries into discovery refresh on the hot path

Why:

- when IBKR itself is unhealthy, rediscovery only adds noise and timeouts

### 4. Mentor persisted review threads for qualified alerts

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/MentorSignalPanel.tsx`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/lib/mentor.ts`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/MentorPanel.tsx`

What changed:

- selected trading alerts now capture a frozen Mentor payload snapshot at alert time on the backend
- the first Mentor review for that alert is saved in PostgreSQL and broadcast to the UI
- each alert now owns a persisted review thread keyed by the exact alert occurrence
- opening an alert in the panel reads the saved thread only; it does not recompute current market context
- manual reanalysis is explicit through `Reanalyse`, which appends a new saved revision to the same thread
- `/api/mentor/auto-alerts/recent` exposes persisted recent reviews
- `/api/mentor/auto-alerts/thread` returns the saved thread for one alert
- `/api/mentor/auto-alerts/reanalyze` appends a new review revision using a fresh live payload plus `original_alert_context`
- `/topic/mentor-alerts` pushes thread updates to the frontend
- manual `Ask Mentor` runs are intentionally separate from alert reviews and can be listed via `/api/mentor/manual-reviews/recent`

Qualified alert families:

- `SMC` with `BOS` or `CHoCH`
- `MACD` bullish/bearish cross
- `WAVETREND` bullish/bearish cross and overbought/oversold
- `RSI` overbought/oversold
- `ORDER_BLOCK` when VWAP is inside the block

Important behavior:

- the auto-review flow keeps portfolio context disabled
- the panel is intended for trade-conformance review, not account-risk judgment
- the initial review is snapshot-based and no longer depends on click-time market state
- manual reanalysis does rebuild current indicators/candles/live price, but also passes the original alert time/reason/price to Gemini for comparison

### 5. Mentor trade outcome tracker

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/TradeSimulationService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/domain/model/TradeSimulationStatus.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/entity/MentorSignalReviewEntity.java`

What changed:

- saved alert reviews now carry simulation fields for post-trade outcome tracking
- valid reviews with a complete `Entry / SL / TP` plan are initialized as `PENDING_ENTRY`
- invalid or incomplete plans are initialized as `CANCELLED`
- a scheduled backend service replays subsequent `1m` candles from PostgreSQL and updates the review outcome asynchronously

Simulation rules:

- before entry:
  - if `TP` is touched before the limit `Entry`, the result becomes `MISSED`
  - if the limit `Entry` is touched, the trade becomes `ACTIVE`
- after entry:
  - `SL` resolves to `LOSS`
  - `TP` resolves to `WIN`
- if one candle crosses both `SL` and `TP`, the result is recorded as `LOSS` pessimistically
- `maxDrawdownPoints` records the worst adverse excursion before resolution

Operational note:

- this uses the existing internal candle repository only
- no external replay feed or provider is involved

### 6. Transition-based alert evaluation and grouped reviews

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/domain/alert/service/IndicatorAlertEvaluator.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/AlertService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/MentorSignalReviewService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/MentorSignalPanel.tsx`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/Dashboard.tsx`

What changed:

- `IndicatorAlertEvaluator` switched from state-based to transition-based detection: alerts only fire when a condition *changes*, not when it persists across polling cycles
- uses a `ConcurrentHashMap<String, String>` to track last-known state per indicator/instrument/timeframe
- `AlertService` now publishes individual alerts to WebSocket but batches Mentor reviews by direction for alerts that fire in the same polling cycle
- `MentorSignalReviewService.captureGroupReview()` groups alerts by direction and creates one combined review per group
- `MentorSignalPanel` groups alerts in the UI by instrument+timeframe+direction within a 90-second time window
- added instrument filter dropdown to `MentorSignalPanel`
- removed AI JSON export button and bottom `AlertsFeed` ticker from `Dashboard`

Why:

- persistent conditions (e.g., RSI overbought, BOS) were re-firing every 300s (dedup cooldown) across all instruments simultaneously
- multiple indicators reacting to the same market move at the same time should produce one combined review, not N separate reviews

### 7. IBKR Client Portal watchlist import persisted in PostgreSQL

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/IbkrWatchlistService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/ClientPortalIbkrWatchlistSource.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/presentation/controller/IbkrPortfolioController.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/Dashboard.tsx`

What changed:

- RiskDesk can now import user watchlists from IBKR Client Portal on demand
- imported watchlists are stored in PostgreSQL and exposed through `/api/ibkr/watchlists`
- import is triggered through `POST /api/ibkr/watchlists/import`
- the dashboard can use imported watchlists to populate the instrument selector with a local fallback to `MCL/MGC/E6/MNQ`

Why:

- the header instrument list should no longer depend only on hardcoded values
- watchlist metadata is allowed to come from Client Portal as a one-shot import, while market data remains on the native `IBKR Gateway -> PostgreSQL -> internal services` path

### 8. Dynamic dashboard fallback for non-enum watchlist instruments

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/WatchlistDashboardService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayWatchlistMarketDataAdapter.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/presentation/controller/CandleController.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/presentation/controller/IndicatorController.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/presentation/controller/LivePriceController.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/Dashboard.tsx`

What changed:

- the dashboard instrument selector is now driven by imported watchlist instruments, not a fixed frontend list
- non-enum symbols can now hit the existing dashboard endpoints:
  - `/api/candles/{instrument}/{timeframe}`
  - `/api/indicators/{instrument}/{timeframe}`
  - `/api/indicators/{instrument}/{timeframe}/series`
  - `/api/live-price/{instrument}`
- a new PostgreSQL-backed cache table `watchlist_candles` stores candles for dynamic symbols
- the backend resolves watchlist instruments from imported metadata and fetches candles/live snapshot from IB Gateway using `conid`
- frontend ticker now polls REST live prices for symbols that are not fed by the core WebSocket stream
- exact contract selection now uses a stable selector:
  - `conid:<id>` when IBKR provides a contract id
  - `local:<LOCAL_SYMBOL>` as fallback for rows without conid
  - `symbol:<SYMBOL>` / `code:<CODE>` only as weaker fallbacks
- this prevents different expiries of the same root symbol from collapsing onto one dashboard selection

Important scope boundary:

- this makes the dashboard chart and indicator panels work for arbitrary imported watchlist symbols
- for duplicate root symbols such as multiple `MGC` expiries, the watchlist panel now targets the exact contract rather than the root code
- it does **not** yet migrate mentor, backtest, or manual position entry away from the legacy `Instrument` enum

## Known Runtime Behavior

### Current good news

- the reconnect storm around `clientId=7` appears fixed at the application level
- a single stable TCP session to `127.0.0.1:4001` was observed during testing

### Current remaining issue

Live prices can still return DB fallback instead of native live values when IBKR market data farms are down.

Observed symptoms:

- `/api/live-price/E6` returning:
  - `source = FALLBACK_DB`
- IB Gateway messages such as:
  - `2110`
  - `2103`
  - `2105`
  - `2157`
- native snapshot requests timing out

Interpretation:

- this is an upstream IBKR runtime/connectivity condition
- it is not a reason to add external market data providers

## Verification Commands Used Recently

```bash
mvn -q -DskipTests compile
cd frontend && npm run lint
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/live-price/E6
lsof -nP -iTCP:4001
```

## What an Incoming Agent Should Check First

1. Read `AGENTS.md`
2. Read `docs/PROJECT_CONTEXT.md`
3. Read `docs/ARCHITECTURE_PRINCIPLES.md`
4. Confirm `application.properties` still points to `IB_GATEWAY`
5. Confirm no external market data provider was reintroduced
6. Re-run backend compile and frontend lint before deeper edits

## Suggested Next Improvements

- surface the reason for `FALLBACK_DB` in API/UI
- add explicit health/status around IBKR live market data farm availability
- document and automate the expected IB Gateway startup sequence
- optionally add integration tests around source attribution for live price vs DB fallback
- add more explicit tests around layer boundaries and use-case behavior
- expose the simulation outcome in the UI once the product design is ready
- add tests for transition-based alert evaluation edge cases
- add tests for grouped review batching logic
