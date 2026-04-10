# AI Handoff

Last updated: 2026-04-01

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

### 0. Synthetic DXY replaced the delayed `DX/ICEUS` contract path

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/DxyMarketService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/domain/marketdata/model/SyntheticDollarIndexCalculator.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayFxQuoteProvider.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayFxContractResolver.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/entity/MarketDxySnapshotEntity.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/presentation/controller/DxyMarketController.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/DxyPanel.tsx`

What changed:

- `DXY` is now computed internally from six IBKR FX quotes (`EURUSD`, `USDJPY`, `GBPUSD`, `USDCAD`, `USDSEK`, `USDCHF`) instead of resolving the delayed `DX` future on `ICEUS`
- the calculator prefers `mid=(bid+ask)/2`, falls back to `last`, rejects missing/non-positive values, and rejects snapshots whose component timestamps drift by more than `15s`
- complete snapshots are persisted into `market_dxy_snapshots` with all six FX inputs, the computed DXY value, source, and completeness flag
- `MarketDataService` refreshes the synthetic DXY on the existing poll cycle and publishes `DXY` to `/topic/prices`
- `MentorIntermarketService` now reads only from the synthetic DXY service and uses persisted DXY snapshots for its `10m` / `1h` baseline lookup
- `DXY` is excluded from futures-specific loops such as active-contract resolution, rollover detection, and futures historical refresh
- dedicated REST endpoints now exist at `/api/market/dxy/latest`, `/api/market/dxy/history`, and `/api/market/dxy/health`
- `CLIENT_PORTAL` mode does not serve these DXY endpoints; synthetic DXY is only supported in `IB_GATEWAY` mode

Why:

- the `DX/ICEUS` path was delayed and unsuitable for an internal dollar-index signal used by trading workflows
- the repo already had the required IBKR connectivity, so the correct fix was to stay inside the existing `IBKR Gateway -> PostgreSQL -> internal services` path

### 1. GitHub-hosted Docker image pipeline

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/.github/workflows/docker-image.yml`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/.dockerignore`

What changed:

- Docker image validation now runs in GitHub Actions for `push` to `main` and pull requests targeting `main`
- Docker image publication now runs in GitHub Actions for Git tag pushes
- manual publication is available through `workflow_dispatch` with a `git_tag` input for tags that already exist
- images are published to `ghcr.io/smailnilson/riskdesk2`
- stable tags also refresh the `latest` container tag
- tagged releases can now also be deployed from GitHub Actions over SSH via `docker-compose.release.yml` on the server
- the deploy workflow expects GitHub Actions secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH`, `GHCR_USERNAME`, and `GHCR_TOKEN`
- the private IBKR `tws-api` dependency is vendored in `vendor/maven-repo` so Docker and GitHub Actions builds can resolve it without a developer-local Maven cache

Why:

- release publishing should not depend on running `docker build` locally
- tag-based publishing keeps the release image aligned with an immutable Git ref

### 2. Native IBKR client stabilization

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayNativeClient.java`

What changed:

- reconnect cooldown added
- failed connect attempts no longer thrash reconnects
- controller state is cleared on disconnect/error
- connect errors `326`, `502`, and `504` now trigger a cleaner failure path

Why:

- IB Gateway logs showed repeated `client id already in use` and connection churn

### 3. Direct preconfigured futures contract resolution

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayContractResolver.java`

What changed:

- for known futures instruments, the resolver can use a preconfigured IBKR contract directly via `conid`
- this reduces dependency on `reqContractDetails` on the live-path critical section

### 4. Live provider path simplified

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayMarketDataProvider.java`

What changed:

- live snapshot lookup no longer retries into discovery refresh on the hot path

Why:

- when IBKR itself is unhealthy, rediscovery only adds noise and timeouts

### 5. Mentor persisted review threads for qualified alerts

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

### 6. Mentor trade outcome tracker

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

### 7. Transition-based alert evaluation and grouped reviews

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
- indicator alert dedup is now key-based on the shared short cooldown only; it no longer blocks a full `10m` or `1h` window, so multiple same-timeframe alerts can surface when new transitions occur
- `MentorSignalReviewService.captureGroupReview()` groups alerts by direction and creates one combined review per group
- `MentorSignalPanel` groups alerts in the UI by instrument+timeframe+direction within a 90-second time window
- added instrument filter dropdown to `MentorSignalPanel`
- removed AI JSON export button and bottom `AlertsFeed` ticker from `Dashboard`

Why:

- persistent conditions (e.g., RSI overbought, BOS) were re-firing every 300s (dedup cooldown) across all instruments simultaneously
- multiple indicators reacting to the same market move at the same time should produce one combined review, not N separate reviews

### 8. Execution foundation for real IBKR workflow

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/ExecutionManagerService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/entity/TradeExecutionEntity.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/JpaTradeExecutionRepositoryAdapter.java`

What changed:

- introduced a dedicated `trade_executions` table instead of extending `mentor_signal_reviews` again
- idempotence is now defined per persisted Mentor review ID (`mentorSignalReviewId`)
- `ExecutionManagerService.ensureExecutionCreated()` creates a pending execution foundation row only
- `TradeExecutionRecord` now freezes `quantity` at arming time; this is required before any live broker submission
- `MentorController` now exposes:
  - `POST /api/mentor/executions`
  - `GET /api/mentor/executions/by-review/{mentorSignalReviewId}`
  - `POST /api/mentor/executions/by-review-ids`
- `MentorController` now also exposes `POST /api/mentor/executions/{executionId}/submit-entry`
- `MentorSignalPanel` now supports manual Slice 1 arming against the selected IBKR account and displays the persisted execution state
- Slice 2 now submits a simple IBKR limit entry order through the native gateway adapter
- full fill/re-sync/virtual exit orchestration does not exist yet
- `MentorSignalReview` persistence now stores explicit execution eligibility metadata:
  - `executionEligibilityStatus`
  - `executionEligibilityReason`

Why:

- live execution lifecycle is orthogonal to review persistence and simulation persistence
- one review must never create duplicate live executions under retries, restart replays, or double clicks
- execution gating must not depend on parsing the verdict string in UI code

Operational note:

- the new eligibility field on historical rows may be `null`; treat those rows as legacy, display-only records until they are reanalyzed
- the UI no longer needs to parse verdict text to decide whether a review is execution-eligible
- legacy `trade_executions` rows created before `quantity` existed may need enrichment before they can be submitted live

## Known Runtime Behavior

### Current good news

- the reconnect storm around `clientId=7` appears fixed at the application level
- IB Gateway runs on `riskdesk-prod` (`100.113.139.64:4003`) via Tailscale — NOT on localhost
- local `application-local.properties` must set `riskdesk.ibkr.native-host=100.113.139.64` and `riskdesk.ibkr.native-port=4003`
- verify Tailscale connectivity: `tailscale status | grep riskdesk-prod` and `nc -z 100.113.139.64 4003`

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

### 9. Mentor IA v2 — per-asset-class payloads, tick data, decision hierarchy

Files:

- `src/main/java/com/riskdesk/domain/model/AssetClass.java` (new)
- `src/main/java/com/riskdesk/domain/engine/smc/SessionPdArrayCalculator.java` (new)
- `src/main/java/com/riskdesk/domain/engine/indicators/MarketRegimeDetector.java` (new)
- `src/main/java/com/riskdesk/domain/marketdata/port/TickDataPort.java` (new)
- `src/main/java/com/riskdesk/domain/marketdata/model/TickAggregation.java` (new)
- `src/main/java/com/riskdesk/application/dto/MacroCorrelationSnapshot.java` (new)
- `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/TickByTickAggregator.java` (new)
- `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbkrTickDataAdapter.java` (new)
- `src/main/java/com/riskdesk/application/service/MentorSignalReviewService.java` (modified)
- `src/main/java/com/riskdesk/application/service/GeminiMentorClient.java` (modified)
- `src/main/java/com/riskdesk/application/service/MentorIntermarketService.java` (modified)
- `frontend/app/lib/mentor.ts` (modified)
- `frontend/app/components/MentorSignalPanel.tsx` (modified)
- `frontend/app/lib/api.ts` (modified)

What changed:

- `AssetClass` enum (METALS, ENERGY, FOREX, EQUITY_INDEX) added to domain layer with per-class macro correlation kinds and sector leader mappings
- `Instrument.assetClass()` maps each instrument to its asset class (DXY returns null)
- `SessionPdArrayCalculator` computes intraday Premium/Discount/Equilibrium zones from session high/low
- `MarketRegimeDetector` detects TRENDING_UP/DOWN, RANGING, CHOPPY from EMA alignment + BB width
- `TickDataPort` + `TickAggregation` provide a domain abstraction for real-time order flow
- `TickByTickAggregator` aggregates classified ticks in a rolling 5-minute window with Lee-Ready buy/sell classification and delta divergence detection
- `IbkrTickDataAdapter` implements TickDataPort; falls back to CLV estimation when tick data unavailable
- Mentor payload restructured: sections renamed (market_structure_smc, momentum_oscillators, etc.), enriched with PD Array (session + structural), liquidity pools (EQH/EQL), Bollinger state, CMF, order flow with source tracking
- `MacroCorrelationSnapshot` replaces per-instrument intermarket fields with per-asset-class fields (sector leader, VIX, US10Y)
- `GeminiMentorClient` system prompt is now dynamic per asset class with strict decision hierarchy (Structure 50% > Order Flow 30% > Momentum 20%), rejection rules, winning patterns (Catch-up Trade, Kill Zone, CHoCH Pullback), and Entry/SL/TP formulas
- Frontend updated: mentor.ts payload mirrors backend structure, MentorSignalPanel shows color-coded asset class badges

Important behavior:

- the `order_flow_and_volume` payload section includes a `source` field ("REAL_TICKS" or "CLV_ESTIMATED") so Gemini knows the data quality
- macro correlation fields for VIX, US10Y, Silver are null with `data_availability: "DXY_ONLY"` until IBKR subscriptions for those instruments are added
- the decision hierarchy is enforced only in the Gemini system prompt, not as Java pre-scoring gates
- `IbGatewayNativeClient` does NOT yet call `reqTickByTickData()` — the TickByTickAggregator and IbkrTickDataAdapter are ready to receive ticks but the subscription wiring in the native client is a future slice

Why:

- universal payload with no asset class differentiation produced generic Gemini verdicts that didn't account for metals-specific correlations (Silver leader) or equity-index-specific risks (VIX spikes)
- CLV-based delta estimation lacks precision for real order flow analysis; the tick infrastructure prepares for real data
- the decision hierarchy prevents Gemini from weighting contradictory indicators equally

### 10. Alert pipeline overhaul — 5 fixes for signal coverage gaps

Files:

- `src/main/java/com/riskdesk/domain/alert/service/IndicatorAlertEvaluator.java`
- `src/main/java/com/riskdesk/domain/alert/model/IndicatorAlertSnapshot.java`
- `src/main/java/com/riskdesk/domain/alert/model/SignalWeight.java`
- `src/main/java/com/riskdesk/domain/alert/service/SignalPreFilterService.java`
- `src/main/java/com/riskdesk/application/service/AlertService.java`
- `src/main/java/com/riskdesk/application/service/BehaviourAlertService.java`

What changed:

**Fix A — Order Block TOUCH events:**
- `IndicatorAlertEvaluator` now fires an `ORDER_BLOCK` alert when price enters an active OB zone (transition-based: OUTSIDE→INSIDE on candle close)
- Previously only MITIGATION and INVALIDATION lifecycle events generated OB alerts
- `SignalWeight.fromAlert()` now qualifies OB messages containing "ENTERED" (in addition to MITIGATED/INVALIDATED), yielding weight 3.0 (standalone flush)

**Fix B — Strong S/R touch → confluence pipeline:**
- `BehaviourAlertService` now routes `STRONG_HIGH` and `STRONG_LOW` S/R touches through `SignalConfluenceBuffer` in addition to the existing MONITOR behaviour review
- New `SignalWeight.SR_TOUCH` (1.0, family "Niveaux") added for SUPPORT_RESISTANCE alerts
- Direction inference: STRONG_LOW → LONG (support bounce), STRONG_HIGH → SHORT (resistance rejection)
- S/R touches contribute to confluence weight but do not flush standalone

**Fix C — Pre-filter: H1 excluded from LTF + standalone bypass:**
- `SignalPreFilterService.isLtf` changed from `!"4h"` to `!"4h" && !"1h"` — H1 signals are no longer subject to HTF trend filtering
- Standalone signals (weight ≥ 3.0: CHoCH, BOS, WaveTrend, OB, CMF extreme) now bypass the HTF trend filter even on 5m/10m — structural breaks are inherently counter-trend and should not be suppressed
- Weak signals (EMA, MACD, RSI, Stochastic, etc. at weight 1.0) remain filtered by HTF trend on 5m/10m

**Fix D — Pre-filter logging visible in prod:**
- `log.debug` → `log.info` for blocked signal messages in `SignalPreFilterService` — blocked signals are now visible in production logs

**Fix E — CMF extreme dual-path into qualified pipeline:**
- New `cmfExtremeSignal` field added to `IndicatorAlertSnapshot` (ACCUMULATION / DISTRIBUTION / NEUTRAL)
- `IndicatorAlertEvaluator` now fires a `CHAIKIN` category alert on CMF extreme transitions (>0.40 or <-0.40)
- These alerts enter the normal qualified pipeline with weight 3.0 (standalone flush to Mentor)
- The existing `ExtremeCmfZoneRule` behaviour path remains active in parallel for MONITOR reviews

Why:

- Production audit revealed multiple signal coverage gaps: OB zone entries, strong S/R levels, and CMF extremes were either invisible to the qualified pipeline or routed exclusively through the passive MONITOR path
- H1 counter-trend signals were passing the pre-filter (H4 trend = UNDEFINED) but wasting Gemini API quota — excluding H1 from LTF prevents future waste when H4 data becomes available
- Standalone structural signals (CHoCH, BOS) are counter-trend by definition — blocking them via HTF filter defeated their purpose

## Suggested Next Improvements

- wire `reqTickByTickData("AllLast")` into `IbGatewayNativeClient` and connect to `IbkrTickDataAdapter.onTickByTickTrade()`
- add IBKR subscriptions for VIX (IND/CBOE), ZN/US10Y (FUT/CBOT), SI (FUT/COMEX) to populate macro correlation fields
- compute session PD Array in `IndicatorService.computeSnapshot()` using session candles from `TradingSessionResolver`
- wire `MarketRegimeDetector` into the payload (add `market_regime_context` section to `buildPayload()`)
- surface the reason for `FALLBACK_DB` in API/UI
- add explicit health/status around IBKR live market data farm availability
- expose the simulation outcome in the UI once the product design is ready
- add tests for transition-based alert evaluation edge cases
