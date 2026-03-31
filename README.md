# RiskDesk — Risk Management Dashboard for Futures Traders

Multi-instrument risk management SaaS with SMC (Smart Money Concepts) indicator engine.

## Stack
- **Backend:** Spring Boot 3.2 / Java 21
- **Database:** PostgreSQL 16 (H2 for dev)
- **Real-time:** WebSocket (STOMP)
- **Containerization:** Docker / Docker Compose

## Instruments
- MCL (Micro WTI Crude Oil)
- MGC (Micro Gold)
- 6E (Euro FX Futures)
- MNQ (Micro E-mini Nasdaq-100)

## Indicator Engine (Phase 1)

### Technical Indicators
| Indicator | Settings | Source |
|-----------|----------|--------|
| EMA | 9, 50, 200 (close) | Custom |
| RSI | 14 (levels: 33/40/60) | Custom |
| MACD | 12, 26, 9 | Custom |
| Supertrend | Period 10, Factor 3 | Custom |
| VWAP | Session | Custom |
| Chaikin Oscillator | 3, 10 | Custom |
| CMF | 20 | Custom |

### SMC Indicators
| Indicator | Settings | Source |
|-----------|----------|--------|
| Market Structure (BOS/CHoCH) | Swing lookback 5 | Custom (LuxAlgo-inspired) |
| Order Blocks | Period 10, Max 3 | Custom (LuxAlgo-inspired) |
| Strong/Weak High/Low | Auto-detected | Custom |

## Quick Start

### Option 1: Local dev (H2 in-memory DB)
```bash
./mvnw spring-boot:run
```
API available at http://localhost:8080

### Option 2: Docker Compose (PostgreSQL)
```bash
docker-compose up -d
```

### Build metadata for deployments
To make `/actuator/info` report the exact build running in production, build the
backend image with explicit metadata:

```bash
docker build \
  --build-arg APP_VERSION=0.1.0-SNAPSHOT \
  --build-arg APP_GIT_SHA="$(git rev-parse HEAD)" \
  --build-arg APP_IMAGE_TAG="$(git rev-parse --short HEAD)" \
  --build-arg APP_BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t riskdesk-backend:$(git rev-parse --short HEAD) .
```

Then verify on the running app:
```bash
curl http://localhost:8080/actuator/info
```

### Option 3: Named profile `local-ibkr-gcp`
Use this when the local SaaS must run against the IBKR Gateway hosted on the
GCP VM over an IAP tunnel.

Open the tunnel in one terminal:
```bash
./scripts/open-gcp-ibkr-tunnel.sh local-ibkr-gcp
```

Start the local backend + frontend in another terminal:
```bash
./scripts/start-saas.sh local-ibkr-gcp
```

Stop the local backend + frontend:
```bash
./scripts/stop-saas.sh local-ibkr-gcp
```

Default profile file:
`scripts/profiles/local-ibkr-gcp.env`

## API Endpoints

### Positions
```
GET    /api/positions          # Open positions
GET    /api/positions/closed   # Closed positions
GET    /api/positions/summary  # Portfolio summary (P&L, exposure, margin)
POST   /api/positions          # Open new position
POST   /api/positions/{id}/close  # Close position
```

### Indicators
```
GET    /api/indicators/{instrument}/{timeframe}
# Example: GET /api/indicators/MCL/10m
# Returns: EMA, RSI, MACD, Supertrend, VWAP, Chaikin, SMC structure, Order Blocks
```

### WebSocket
```
Connect: ws://localhost:8080/ws
Subscribe: /topic/prices     # Live price updates
Subscribe: /topic/alerts     # Risk & indicator alerts
```

## Example: Open a Position
```bash
curl -X POST http://localhost:8080/api/positions \
  -H "Content-Type: application/json" \
  -d '{
    "instrument": "MCL",
    "side": "SHORT",
    "quantity": 3,
    "entryPrice": 62.40,
    "stopLoss": 63.20,
    "takeProfit": 60.80,
    "notes": "Bearish CHoCH on 1h, OB rejection at 62.50"
  }'
```

## Project Structure
```
src/main/java/com/riskdesk/
├── RiskDeskApplication.java
├── config/          # WebSocket, CORS config
├── controller/      # REST endpoints
├── dto/             # Request/Response objects
├── engine/
│   ├── indicators/  # EMA, RSI, MACD, Supertrend, VWAP, Chaikin
│   └── smc/         # Market Structure, Order Blocks
├── model/           # JPA entities (Position, Candle, Instrument)
├── repository/      # Spring Data JPA repos
└── service/         # Business logic
```

## Roadmap
- [ ] Phase 1: Core risk management + Phase 1 indicators
- [ ] Phase 2: Order Blocks, Bollinger Bands, Delta Flow Profile
- [ ] Phase 3: WaveTrend, Trendlines with Breaks, Sniper signals
- [ ] Frontend: React/Next.js dashboard with lightweight-charts
- [ ] Alerts: Telegram bot for BOS/CHoCH and risk threshold alerts
