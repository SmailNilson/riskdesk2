# auto_trader — Design Notes

## Decisions encoded in the v0.1 implementation

| Sujet | Décision | Pourquoi |
|---|---|---|
| Stack | Python autonome (`pandas-ta`/numpy), `ib_insync` | Projet séparé, n'interfère pas avec le backend Java |
| Swing High/Low | `min(low[-Y:])` / `max(high[-Y:])` + buffer ticks | Simple, déterministe ; le buffer évite le stop-hunting |
| WT cross | LazyBear strict : cross WT1>WT2 **et** WT1 ≤ OS (LONG) / WT1 ≥ OB (SHORT) | Match TradingView LazyBear |
| Sync RSI/WT | `lookback_bars` **symétrique** (± autour du cross WT) | "X bougies après ou avant" du brief |
| Confirmation | Chaikin Osc(3,10) — `>0` LONG / `<0` SHORT → contracts × 2 | Spec littérale |
| Entrée | Open de la bougie N+1 après confirmation (causal) | Aucun look-ahead |
| SL | Swing − buffer (LONG) / Swing + buffer (SHORT), rounded au tick | Spec littérale |
| TP | Optionnel, R-multiple (désactivé par défaut) | Le brief dit "prévois TP/trailing en variables" |
| Trailing | ATR(N) × multiplier, activable via config | Idem |
| Concurrence | 1 trade max simultané par direction (paramétrable) | Évite pyramide imprévue |
| Intra-bar | Si SL et TP touchés dans la même bougie → SL gagne | Pessimisme cohérent avec `TradeSimulationService` du backend Java |
| Reverse | Pas de reverse automatique en v0.1 (sortie sur SL/TP ou fin de série) | Garder l'implémentation simple ; reverse à ajouter sur demande |

## Variables exposées (config YAML)

Toutes les valeurs réglables vivent dans `config/strategy_*.yaml`. Les "X" et "Y" du brief :
- `signals.lookback_bars` → **X** (sync WT/RSI)
- `risk.swing_lookback` → **Y** (fenêtre du swing SL)
- `risk.swing_buffer_ticks` → buffer SL en ticks
- `risk.take_profit_r_multiple`, `risk.trailing_atr_*` → TP / trailing facultatifs
- `confirmation.enabled` / `kind` → on/off et type de confirmateur

## À venir (slices suivantes)

- Tests sur **vraies données MNQ** (export depuis la base PostgreSQL du backend → CSV)
- Walk-forward / optimisation des paramètres (`X`, `Y`, OB/OS) — grid + métriques
- Option `entry_mode = signal_close` (pour mode agressif)
- Branche `reverse_on_opposite_signal` (close-and-reverse) si demandé
- Vrai feed live IBKR via `reqRealTimeBars` au lieu du `reqHistoricalData` poll-based
- Persistence des trades live dans la base PostgreSQL existante (réutiliser `trade_executions`)
- Notifications Telegram (réutiliser `TELEGRAM_BOT_TOKEN` du backend)
