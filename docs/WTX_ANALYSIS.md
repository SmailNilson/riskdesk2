# WTX — Analyse données & stratégie + recommandations

> Synthèse de l'analyse WaveTrend-XT (MNQ 5m/10m). Construite à partir de backtests
> rejouant le **moteur réel** (`WaveTrendIndicator` / `WtxBarEvaluator` / `WtxHtfBiasFilter` /
> `WtxTrailingExitEvaluator` / `MarketRegimeDetector`) sur les bougies internes IBKR→PostgreSQL.
> Dernière mise à jour : 2026-06-07.

## Méthode & garde-fous
- Rejeu fidèle au live (config prod : n1=10/n2=21/sig=4, OB/OS ±53, les 4 flags signal ON,
  reverseOnOpp, SL 1.3×ATR, trailing POINTS arm 30 / trail 15 sur MNQ).
- Échantillon : 5m ≈ 58 j (11 534 barres) · 10m ≈ 151 j (14 920 barres) · 1h pour le biais HTF.
- **Paper, qty 1, 2 $/pt, zéro coût/slippage, NY-flatten & max-loss non modélisés.**
- Validation : les **totaux** du harnais collent à l'équity live (HTF 10m ≈ +10 k$, BASELINE ≈ nul).
- ⚠️ Le **régime** est une *grille d'analyse* (EMA9/50/200 + largeur Bollinger), **pas** une
  brique live. Le découpage $ par régime dépend de la méthode de tagging → à lire en **relatif**
  (BASELINE vs HTF dans le même régime), pas comme des chiffres absolus.

---

## 1. Classement des profils (Swing OFF)
| Profil | 5m | 10m | Verdict |
|---|---|---|---|
| **HTF** | **+5 606 $** · PF~1.24 · WR 53 % | **+10 972 $** · PF~1.26 · WR 58 % | ✅ **le meilleur** |
| SESSION_ATR | +~2 000 $ | +~5 500 $ | ok mais < HTF |
| BASELINE | −502 $ · WR 36 % | −353 $ · WR 35 % | ❌ aucun edge (PF~1.0) |
| STRICT | négatif (~22 trades) | négatif (~24 trades) | ❌ sur-filtré, inutilisable |

- **HTF** = SESSION_ATR + filtre de biais 1h (EMA21/55) : n'autorise que les crosses **dans le
  sens de la tendance 1h**. C'est un **simple toggle**, pas de code.
- **Swing-bias = OFF** : redondant par-dessus HTF (deux filtres de tendance) → il coupe des
  trades gagnants (10m : HTF seul +10 972 vs HTF+Swing ~+4 200).

## 2. Régime × timeframe (BASELINE vs HTF, même tagging)
**MNQ 5m**
| Régime | BASELINE | HTF |
|---|---|---|
| RANGING | +203 $ (WR 34 %) | **+3 739 $** (WR 53 %) |
| CHOPPY | **−826 $** | +37 $ |
| TREND | +121 $ (≈ breakeven) | **+1 830 $** (WR 55 %) |

**MNQ 10m**
| Régime | BASELINE | HTF |
|---|---|---|
| RANGING | +3 457 $ (WR 33 %) | **+4 011 $** (WR 55 %) |
| CHOPPY | −652 $ | **+4 242 $** (WR 60 %) |
| TREND | **−3 158 $** (WR 35 %) | **+2 719 $** (WR 59 %) |

**Lecture :**
- **RANGING = le moteur de l'edge** (positif partout ; meilleur sous HTF).
- **CHOPPY ne marche que sur le 10m, ET seulement sous HTF** (+4 242). En BASELINE le choppy est
  **négatif sur les deux TF** (−826 / −652). La cadence lente du 10m digère le bruit qui déchire le 5m
  → c'est *pourquoi* le 10m est le TF prioritaire.
- **TREND : mauvais sans filtre de sens** (BASELINE/SESSION_ATR), **rendu rentable par HTF**
  (qui interdit les crosses à contre-tendance).

## 3. BASELINE est-il bon pour le TRENDING ? → **NON** (vérifié)
- 5m TREND : **+121 $** (WR 37 %) = bruit/breakeven. 10m TREND : **−3 158 $** (WR 35 %) = **pire bucket**.
- HTF bat BASELINE en tendance, large, sur les 2 TF : **+1 830 vs +121** (5m), **+2 719 vs −3 158** (10m).
- Le cas du **05/06** (10m BASELINE +691 battant HTF +418 un jour de forte chute) était un
  **outlier n=1** : sur 151 j, le 10m BASELINE en tendance perd −3 158 $.
- Mécanique : WTX est un système de **cross mean-reversion** ; en tendance il *fade* le mouvement.
  BASELINE sans stop tient ces positions à contre-sens → saigne. **HTF, pas BASELINE, est la réponse
  au trending.**

## 4. Stop / Take-profit
- **SL 1.3×ATR = trop serré = fuite n°1.** Les perdants sont des `INITIAL_STOP` avec MAE profond /
  MFE quasi nul (stoppés net puis le trade aurait marché). L'élargir à **1.6–2.0×ATR améliore
  simultanément P&L + PF + win rate** (5m PF 1.24→1.32, WR 53→59 % ; 10m PF 1.26→1.34, WR 57→63 %).
  → **plus gros levier** (+~1 700 $ / +~2 750 $).
- **Aucun TP** : un TP dur plafonne les gros gagnants ; tout le profit vient du `TRAILING_STOP`. Ne pas en ajouter.

## 5. Sessions
- **NY (AM + PM) fait l'argent.** **Asia/overnight ET Londres (sur 5m)** sont faibles/négatifs
  (liquidité fine, biais 1h périmé, faux crosses).
- → cœur = **NY** ; **éviter les entrées Asia/overnight** ; Londres douteux sur 5m.

## 6. Mécanique (rappel)
- Event-driven (`WtxStrategyService.onCandleClosed`) : la stratégie **connaît son état**
  FLAT/LONG/SHORT par (instrument, TF) (`WtxStrategyState.currentPosition`, persisté).
- Elle n'agit **que sur un crossover WT frais** à la clôture de barre (pas de scan continu d'un
  « potentiel »). Après sortie trailing → FLAT → prochain cross dans n'importe quel sens ; sous HTF,
  la ré-entrée suit le biais 1h.

---

## 7. Recommandations (par priorité)
> ✅ = implémenté (2026-06-07, voir `docs/AI_HANDOFF.md`). Tout réversible par config, Auto-IBKR reste OFF.

1. ✅ **HTF + Swing OFF** sur **10m (prioritaire)** et **5m** — défaut code persistant pour MNQ
   (`WtxDefaultProfileBootstrap`, n'écrase pas un choix manuel).
2. ✅ **SL élargi à 2.0×ATR** (`riskdesk.wtx.sl-atr-mult=2.0`). Pas de TP (inchangé).
3. ✅ **Filtre de session** : entrées bloquées **18:00 → 03:00 ET** (Asia/overnight), DST-safe.
4. ✅ **Badge régime (avertissement)** : ⚠ TENDANCE affiché en TRENDING. **Pas de gate** — HTF gère
   déjà le trending ; skip-TREND et profil-adaptatif font **moins bien** que HTF seul.
5. ⏸️ **Max-loss** : **gardé tel quel** ($500 global) par choix. (Piste future : cap $/contrat + seuils serrés.)
6. **Auto-IBKR OFF (paper)** ; valider en **forward ≥ 100 trades** (WR ≥ 45 %, **PF net > 1.10 après coûts**).
7. **Données 1m** : plafond **IBKR ~1 mois** d'historique intraday → la magnitude fine
   (trailing/SL au point près) **n'est pas validable en historique**. La vérité = l'observation forward paper.

## 8. Confiance
- **Directions robustes** (mécaniquement explicables, améliorent plusieurs métriques) : HTF > tout ;
  BASELINE sans edge ; TREND réglé par HTF (pas BASELINE) ; SL trop serré ; pas de TP ; NY = la session.
- **Magnitudes = plafonds paper** (qty 1, close-de-barre, zéro coût). En réel : viser **40–60 %** des $ affichés.
- Découpage par régime = **relatif** (méthode de tagging d'analyse, non live).
