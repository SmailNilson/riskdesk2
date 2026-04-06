# Daily Code Audit Report - 2026-04-06

**Scope**: Commits `4e85f56..ccacdc1` on `main` (PRs #86-#93, revert included)
**Pipeline**: Nettoyeur > Architecte > Dev Senior > QA & Securite

---

## Executive Summary

**17 issues identified** across backend and frontend, **4 critical-path items** requiring immediate attention. The most impactful recent event is the revert of PRs #91-93 (OI contract resolution + behaviour alert batching), which was clean but highlights a gap in integration testing at startup. The new ONIMS correlation engine (#87-88) is well-designed at the domain level but has input validation and error handling gaps at the controller/service layers. No timezone violations (`ZoneId.systemDefault()`, bare `LocalDate.now()`) were found -- the codebase is clean on that front.

**Build/test status**: Not verified (build tools unavailable in scheduled worktree context). Frontend lint and backend compile should be validated as part of morning CI.

---

## Step 1: Agent "Nettoyeur" (Dead Code & Optimization)

### Validated Clean
- No unused imports in recently changed files
- No `ZoneId.systemDefault()`, bare `LocalDate.now()`, or `SimpleDateFormat` anywhere in `src/`
- No `TODO`/`FIXME`/`HACK` markers in production code (only 1 `XXX` in a comment in `SyntheticDxyCalculator.java` documenting currency pair convention -- acceptable)
- Revert of #91-93 was clean: no orphaned method calls, dead imports, or stale config

### Issues Found

| # | File | Issue | Severity |
|---|------|-------|----------|
| N1 | `IbGatewayContractResolver` | `detectInstrument()` does 9 sequential string comparisons instead of a static `Map<String, Instrument>` lookup | LOW |
| N2 | `IndicatorService` | `FVG_DEDICATED_TIMEFRAME = null` field is never reassigned; dead conditional at usage site | LOW |

---

## Step 2: Agent "Architecte" (SOLID & Design)

### Validated Clean
- Hexagonal layering respected: no Spring/JPA in domain, controllers in presentation only
- `CrossInstrumentCorrelationEngine` (domain) is exemplary: stateless enum, AtomicReference for CAS state transitions, volatile for scalars, no Spring dependency
- `CorrelationState` enum and `CrossInstrumentSignal` record are immutable and well-designed
- `TradingSessionResolver` is immutable, stateless, DST-safe via `ZonedDateTime`

### Issues Found

| # | File | Issue | Severity |
|---|------|-------|----------|
| A1 | `CrossInstrumentAlertService` | SRP violation: event listening, evaluation, session/VIX filtering, WebSocket publishing, config management, and REST reporting in one class (~388 lines) | MEDIUM |
| A2 | `CorrelationController` | Untyped `Map<String, Object>` on `POST /config` endpoint. Should use a typed DTO to enforce ISP and enable validation | MEDIUM |
| A3 | `MarketDataService` | SRP violation: price polling, candle accumulation, WebSocket push, alert evaluation, database fallback -- too many concerns in one service | MEDIUM |
| A4 | `CrossInstrumentSignal` | `leaderInstrument` and `followerInstrument` are raw `String` instead of `Instrument` enum -- no type safety | LOW |

---

## Step 3: Agent "Dev Senior" (Clean Code & Quality)

### Validated Clean
- Naming is explicit and intention-revealing across all recent files
- Domain classes are well-documented where logic is non-obvious
- Frontend components follow React patterns correctly (stable useCallback deps, proper useEffect cleanup)
- `useWebSocket.ts` dependencies are all stable (empty `[]`) -- no infinite reconnection loop

### Issues Found

| # | File | Issue | Severity |
|---|------|-------|----------|
| D1 | `IbGatewayHistoricalProvider` | `parseBarTime()` falls back to `Instant.now()` on parse failure. Silent timestamp corruption -- a candle from days ago gets "now" as its time, propagating through indicators | **HIGH** |
| D2 | `CrossInstrumentAlertService` | Session windows hardcoded (`LocalTime.of(9,30)`, `LocalTime.of(11,30)`, etc.) and `DEFAULT_VIX_THRESHOLD = 20.0` -- should be in `application.yml` | LOW |
| D3 | `IbGatewayHistoricalProvider` | Multiple hardcoded magic numbers: `MAX_BACKFILL_CHUNKS=32`, fallback zone `"America/Chicago"`, buffer `+2` days | LOW |

---

## Step 4: Agent "QA & Securite" (Bugs, Security, Edge Cases)

### Validated Clean
- No XSS vulnerabilities in frontend (React safe rendering, no `dangerouslySetInnerHTML`)
- No SQL injection risk (JPA parameterized queries throughout)
- No `@SuppressWarnings` in production code (only in tests for mock generics)
- Error handling in controllers uses typed exceptions (`IllegalArgumentException`, `IllegalStateException`) with proper HTTP status codes
- Frontend `useWebSocket` cleanup properly deactivates STOMP client

### Critical-Path Issues (Fix First)

| # | File | Line(s) | Issue | Severity |
|---|------|---------|-------|----------|
| **Q1** | `CorrelationController` | 63-69 | **ClassCastException crash**: `((Number) body.get("vixThreshold")).doubleValue()` with no null check. Malformed JSON crashes the endpoint with 500 instead of 400 | **HIGH** |
| **Q2** | `IbGatewayHistoricalProvider` | 242 | **Silent data corruption**: `Instant.now()` fallback on parse failure masks bad IBKR data, corrupting candle timestamps downstream | **HIGH** |
| **Q3** | `MarketDataService` | 132-138 | **Race condition**: Three separate `put()` calls on three `ConcurrentHashMap`s (price, timestamp, source). A concurrent read sees mismatched tuples. Should combine into a single `PriceSnapshot` record | **MEDIUM** |
| **Q4** | `IbGatewayContractResolver` | 28-50 | **Non-atomic cache refresh**: Two threads can both pass the null-check and both call `refresh()`, causing duplicate expensive IBKR queries. Use `computeIfAbsent()` | **MEDIUM** |

### Other QA Issues

| # | File | Issue | Severity |
|---|------|-------|----------|
| Q5 | `CrossInstrumentAlertService` (211, 349) | Silent error swallowing: evaluation errors and WebSocket failures logged at `debug` level -- invisible in production | MEDIUM |
| Q6 | `MarketDataService` (200, 235) | Same pattern: WebSocket send failures and price fetch timeouts logged at `debug` | MEDIUM |
| Q7 | `MarketDataService` (116-121) | `databaseFallbackActive` volatile flag has non-atomic read-check-write; two threads can both log "recovered" | LOW |
| Q8 | Frontend `useWebSocket.ts` (52, 69, 120, 124) | 4 empty `.catch(() => {})` handlers silently swallow fetch errors -- no logging or user notification | MEDIUM |
| Q9 | Frontend `Dashboard.tsx` (36, 40) | Empty `catch {}` on API calls (`getPortfolioSummary`, `getIndicators`) -- no error state, UI shows stale data silently | MEDIUM |
| Q10 | Frontend `useWebSocket.ts` (65) | Date sort `new Date(b.createdAt).getTime()` -- if `createdAt` is malformed, `NaN` comparison makes sort order undefined | LOW |

---

## Reverted PRs Analysis (#91-93)

The revert (`ccacdc1`) was clean and correct. Root causes:

1. **PR #93 bug**: `cacheResolved()` called `clearCache() + resolve()` which re-ran IBKR resolution with min-expiry logic, defeating the OI selection from PR #92
2. **PR #91 approach**: Behaviour alert batching deduplication logic was too simplistic at the loop level

**Re-implementation recommendations**:
- Separate "which contract" from "how to cache it" (direct cache mutation, not clear+refresh)
- Add startup integration tests verifying selected contract propagates to downstream services
- Use `AtomicReference<ContractDetails>` for the cache entry to guarantee atomic swap
- Behaviour alert batching should use a dedicated grouping step after all evaluations complete

---

## Proposed Fixes (Priority Order)

### 1. CorrelationController -- Type-safe config endpoint
```java
// Replace Map<String, Object> with typed DTO
public record CorrelationConfigRequest(
    @Nullable Double vixThreshold,
    @Nullable Integer blackoutDurationMinutes
) {}
```

### 2. IbGatewayHistoricalProvider -- Reject bad timestamps
```java
// Replace Instant.now() fallback with exception
} catch (DateTimeParseException ex) {
    log.warn("Unparseable bar time '{}' for {} -- skipping bar", raw, barTimeStr);
    return -1L; // sentinel, filter out in caller
}
```

### 3. MarketDataService -- Atomic price state
```java
// Combine three maps into one
record PriceSnapshot(BigDecimal price, Instant timestamp, String source) {}
private final ConcurrentHashMap<Instrument, PriceSnapshot> priceState = new ConcurrentHashMap<>();
```

### 4. IbGatewayContractResolver -- Atomic cache
```java
// Replace manual check-then-act with computeIfAbsent
public Optional<ContractDetails> resolve(Instrument instrument) {
    ContractDetails result = cache.computeIfAbsent(instrument, this::refreshOrNull);
    return Optional.ofNullable(result);
}
```

### 5. Elevate error logging from debug to warn
All `catch (Exception e) { log.debug(...) }` patterns in `CrossInstrumentAlertService` and `MarketDataService` for WebSocket sends and price fetches should use `log.warn()`.

---

## Pipeline Validation

| Step | Agent | Status |
|------|-------|--------|
| 1 | Nettoyeur (Dead Code) | VALIDATED -- 2 low-severity items |
| 2 | Architecte (SOLID) | VALIDATED -- 4 items, 3 medium |
| 3 | Dev Senior (Clean Code) | VALIDATED -- 3 items, 1 high |
| 4 | QA & Securite (Bugs) | VALIDATED -- 10 items, 2 high, 5 medium |

**Total: 17 findings (2 HIGH, 8 MEDIUM, 7 LOW)**
No blockers for production. HIGH items (Q1, Q2) should be addressed in next sprint.
