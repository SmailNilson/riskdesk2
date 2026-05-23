# auto_trader — MNQ WaveTrend/RSI Trading System (REFERENCE PROTOTYPE)

> **Status :** Prototype Python qui sert de spécification exécutable.
> L'implémentation **prod** vit dans le backend Java sous `src/main/java/com/riskdesk/domain/engine/strategy/wtxrsi/`
> (orchestrée par `WtxRsiStrategyService`). Cette version Python documente la logique
> et offre un backtest standalone pour valider les paramètres avant de re-tirer en Java.

Système de trading automatisé sur **MNQ** (Micro E-mini Nasdaq-100) en **5m** et **10m**.
Backtest + exécution live via Interactive Brokers (`ib_insync`).

## Stratégie

- **WT_X (WaveTrend LazyBear)** channel=10, average=21, OB=53, OS=-53
- **RSI(14)** + **SMA(14)** sur le RSI
- **Confirmation (optionnelle)** Chaikin Oscillator (3,10) ou Order Flow Delta — double la taille
- **SL** swing low/high sur les `Y` dernières bougies (+ buffer ticks)
- **Lookback** la sync WT/RSI tolère ±`X` bougies entre les deux crosses

Toutes les variables (`X`, `Y`, OB/OS, tailles…) vivent dans `config/strategy_*.yaml`.

## Install

```bash
cd auto_trader
python -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env  # ajuste si besoin
```

## Backtest

```bash
auto-trader backtest --config config/strategy_5m.yaml --data data/mnq_5m.csv
```

Input CSV minimal columns: `timestamp,open,high,low,close,volume` (timestamps UTC ISO 8601).

## Live (paper d'abord)

```bash
auto-trader live --config config/strategy_5m.yaml
```

Tant que `AUTO_TRADER_LIVE=0` les ordres sont **simulés** (log seulement). Mettre `=1` pour vraiment router à IBKR.

## Tests

```bash
pytest
```

## Architecture

```
auto_trader/
├── config.py            # YAML → dataclasses (StrategyConfig, RiskConfig…)
├── indicators/          # WaveTrend, RSI+SMA, Chaikin
├── strategy/
│   ├── signals.py       # Détection WT∧RSI synchronisés (±X)
│   ├── risk.py          # Swing SL, sizing, TP/trailing
│   └── state.py         # Position ouverte par TF
├── backtest/
│   ├── engine.py        # Simulateur bar-par-bar
│   ├── data_loader.py   # CSV/parquet → DataFrame
│   └── metrics.py       # Win rate, profit factor, max drawdown
├── live/
│   ├── ibkr_client.py   # Connexion IB Gateway via Tailscale
│   └── executor.py      # Signal → ordre IBKR (bracket entry+SL)
└── cli.py               # entry points
```

## Notes Risque

- MNQ tick = 0.25 pt, $0.50 / tick par contrat
- Tailscale doit être actif vers `riskdesk-prod` (`tailscale status | grep riskdesk-prod`)
- Client-id IBKR par défaut **18** (laisser **8** au backend Java)
