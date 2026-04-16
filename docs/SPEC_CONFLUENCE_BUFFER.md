# SPEC — Confluence Engine (Signal Buffer) pour Mentor Reviews

**Auteur**: PO + BA + Architecte + Dev
**Date**: 2026-04-06 (weights revised 2026-04-16 after standalone-signal tuning)
**Status**: VALIDÉ — implémenté; section 2.3 mise à jour pour refléter le code actuel

---

## 1. Problème actuel

Le système évalue les indicateurs toutes les 3s. Chaque signal crée immédiatement une review Gemini. Les signaux suivants sont bloqués par le semantic dedup. La confluence (convergence de plusieurs indicateurs — le signal le plus fort en ICT/SMC) est détruite.

```
Tick 1 (11:00:00) → RSI SHORT       → Gemini call (RSI seul)
Tick 2 (11:00:06) → WAVETREND SHORT → BLOQUÉ par dedup
Tick 3 (11:00:30) → ORDER_BLOCK SHORT → BLOQUÉ par dedup
```

Gemini analyse un RSI isolé sans savoir que 2 signaux plus forts l'ont confirmé.

---

## 2. Architecture cible : Confluence Engine

### 2.1 Timeframes

| Timeframe | Rôle | Condition |
|-----------|------|-----------|
| **5m** | Entry scalping | Actif **uniquement en kill zones ICT** (London 02:00-05:00 ET, NY 08:30-11:00 ET) |
| **10m** | Entry standard | Toujours actif |
| **1H** | Swing / setup structurel | Toujours actif |
| **4H** | **Filtre passif** | Pas d'alertes directes. Le trend 4H valide les signaux 1H |
| ~~30m~~ | Supprimé | — |

### 2.2 Buffer temporel de confluence

Remplacer `captureGroupReview()` synchrone par un buffer par clé `(instrument, timeframe, direction)`.

**Fenêtres FIXES par timeframe** (pas de glissement — le timer démarre au premier signal et expire sans reset) :

| Timeframe | Fenêtre | Max absolu |
|-----------|---------|------------|
| 5m | 60s | 60s |
| 10m | 120s | 120s |
| 1H | 300s | 300s |

**Règle de flush** :
- **Poids cumulé >= 3.0** → flush **immédiat** (pas besoin d'attendre la fenêtre)
- **Fin de fenêtre** + poids < 3.0 → **pas de review Gemini**, signaux loggés pour backtest
- **Signaux opposés** (LONG + SHORT) → buffers séparés, les deux peuvent flusher si >= 3.0 chacun
- **Pas de flush forcé à la clôture de bougie** — les signaux décalés ne doivent pas être ratés

### 2.3 Système de poids (révisé 2026-04-16)

**Seuil de déclenchement : poids cumulé >= 3.0**

Les poids ont été rehaussés après tuning backtest pour que les signaux
structurels majeurs (ORDER_BLOCK, CHoCH, BOS) déclenchent un flush
**immédiat** (standalone trigger) et que WAVETREND soit quasi-standalone
(il lui faut +0.5 de secondaire pour atteindre 3.0).

| Signal | Poids | Famille | Notes |
|--------|-------|---------|-------|
| ORDER_BLOCK (mitigation/invalidation) | **3.0** | Structure | Standalone trigger |
| CHoCH | **3.0** | SMC | Standalone trigger (relevé de 2.0 → 3.0) |
| BOS | **3.0** | SMC | Standalone trigger — 100% win rate en backtest (relevé de 1.5 → 3.0) |
| WAVETREND (cross/extrêmes) | **2.5** | Oscillateur | Near-standalone — exige +0.5 de secondaire |
| EQH/EQL Sweep | 1.0 | Liquidité | — |
| FVG | 1.0 | Structure | — |
| Supertrend flip | 1.0 | Tendance | — |
| VWAP cross | 1.0 | Niveaux | — |
| EMA cross (Golden/Death) | 1.0 | **Momentum** | **Max 1 avec MACD** |
| MACD cross | 1.0 | **Momentum** | **Max 1 avec EMA** |
| Chaikin Osc cross | 1.0 | **Flow** | **Max 1 avec Delta Flow** |
| Delta Flow shift | 1.0 | **Flow** | **Max 1 avec Chaikin** |
| RSI (extrêmes) | **1.0** | Oscillateur_RSI | Relevé de 0.5 → 1.0 |

**Règle de non-cumul** : au sein d'une même famille (Momentum, Flow), un seul signal compte dans le poids total. EMA cross + MACD cross = 1.0, pas 2.0.

**Structural-anchor gate** (non dans la spec v1) : un buffer qui atteint 3.0
uniquement via des signaux oscillateur/momentum **sans** ancre structurelle
(ORDER_BLOCK / CHoCH / BOS / WAVETREND) est rejeté à l'emission avec un log
`info` — protection contre les flush chop-based. Source de vérité : voir
`SignalConfluenceBuffer#hasStructuralAnchor`.

### 2.4 Signal Primary

Priorité structurelle descendante pour l'affichage et le dedup :

```
ORDER_BLOCK > CHoCH > WAVETREND > BOS > EQH/EQL > FVG >
Supertrend > VWAP > EMA/MACD > Chaikin/Delta > RSI
```

Le primary détermine la `category` de la review dans l'UI et le message principal.

### 2.5 Semantic Dedup

Appliqué **au moment du flush** sur le quadruplet :
```
(instrument, timeframe, direction, primary_signal)
```

Un OB SHORT ne bloque que les futurs OB SHORT. Un CHoCH SHORT qui arrive après peut déclencher une nouvelle review distincte.

---

## 3. Payload Gemini enrichi

```json
{
  "confluence_signals": [
    {"category": "RSI", "message": "RSI overbought at 72.3", "weight": 0.5, "fired_at": "11:00:00"},
    {"category": "WAVETREND", "message": "Bearish Cross", "weight": 2.0, "fired_at": "11:00:06"},
    {"category": "ORDER_BLOCK", "message": "BEARISH OB mitigated [111.40-112.08]", "weight": 3.0, "fired_at": "11:00:30"}
  ],
  "confluence_strength": 3,
  "confluence_weight": 5.5,
  "primary_signal": "ORDER_BLOCK",
  "opposing_buffer_weight": 1.5
}
```

`opposing_buffer_weight` : poids du buffer opposé (LONG pendant un flush SHORT) pour informer Gemini d'une pression contraire.

### 3.1 Règle Gemini (ajout au prompt Niveau 1)

```
- RÈGLE CONFLUENCE : Le champ confluence_weight indique la force du setup.
  Un poids >= 5.0 avec 3+ signaux convergents est un setup A+.
  Un poids de 3.0 avec un seul signal (ex: ORDER_BLOCK seul) reste valide mais nécessite plus de prudence.
  Si opposing_buffer_weight > 0, des signaux contraires s'accumulent — évaluer le risque de reversal.
```

---

## 4. Design technique

### 4.1 Nouveau composant : `SignalConfluenceBuffer`

**Package** : `com.riskdesk.application.service`
**Thread safety** : `ConcurrentHashMap.compute()` — opérations atomiques par clé, pas de `synchronized`.

```java
@Service
public class SignalConfluenceBuffer {

    private final ConcurrentHashMap<String, BufferEntry> buffers = new ConcurrentHashMap<>();
    private final MentorSignalReviewService mentorSignalReviewService;

    // Appelé par AlertService.evaluate() pour chaque signal qualifié
    public void accumulate(Alert alert, String timeframe, String direction,
                           IndicatorSnapshot snap, float weight, String family) {
        String key = alert.instrument() + ":" + timeframe + ":" + direction;
        buffers.compute(key, (k, existing) -> {
            if (existing == null) {
                return new BufferEntry(alert, snap, weight, family, timeframe);
            }
            existing.addSignal(alert, snap, weight, family);
            return existing;
        });
        // Check flush immédiat
        BufferEntry entry = buffers.get(key);
        if (entry != null && entry.effectiveWeight() >= 3.0f) {
            flush(key);
        }
    }

    // Timer : flush les buffers dont la fenêtre fixe a expiré
    @Scheduled(fixedDelay = 5000)
    public void flushExpiredBuffers() {
        Instant now = Instant.now();
        buffers.forEach((key, entry) -> {
            long windowSeconds = windowForTimeframe(entry.timeframe());
            if (now.isAfter(entry.firstSignalTime().plusSeconds(windowSeconds))) {
                flush(key);
            }
        });
    }

    private void flush(String key) {
        BufferEntry entry = buffers.remove(key);
        if (entry == null) return;
        if (entry.effectiveWeight() >= 3.0f) {
            // Lire le poids du buffer opposé
            String oppositeKey = oppositeKey(key);
            float opposingWeight = Optional.ofNullable(buffers.get(oppositeKey))
                .map(BufferEntry::effectiveWeight).orElse(0f);
            mentorSignalReviewService.captureConsolidatedReview(
                entry.signals(), entry.latestSnapshot(), entry.effectiveWeight(),
                entry.primarySignal(), opposingWeight);
        } else {
            // Log pour backtest — poids insuffisant
            logIgnoredSignals(entry);
        }
    }
}
```

### 4.2 BufferEntry

```java
static class BufferEntry {
    private final List<Alert> signals = new ArrayList<>();
    private final Map<String, Float> familyWeights = new HashMap<>();
    private IndicatorSnapshot latestSnapshot;
    private final Instant firstSignalTime;
    private final String timeframe;

    void addSignal(Alert alert, IndicatorSnapshot snap, float weight, String family) {
        signals.add(alert);
        latestSnapshot = snap;
        // Non-cumul par famille : ne garde que le max
        familyWeights.merge(family, weight, Math::max);
    }

    float effectiveWeight() {
        return (float) familyWeights.values().stream()
            .mapToDouble(Float::doubleValue).sum();
    }

    Alert primarySignal() {
        // Retourne l'alerte avec la plus haute priorité structurelle
    }
}
```

### 4.3 Fenêtres par timeframe

```java
private static long windowForTimeframe(String tf) {
    return switch (tf) {
        case "5m"  -> 60;
        case "10m" -> 120;
        case "1h"  -> 300;
        default    -> 120;
    };
}
```

---

## 5. Modifications aux fichiers existants

| Fichier | Changement |
|---------|------------|
| **NOUVEAU** `SignalConfluenceBuffer.java` | Buffer + scheduler + flush logic |
| `AlertService.java:107` | Timeframes `["10m","30m","1h","4h"]` → `["5m","10m","1h"]` |
| `AlertService.java:126-134` | Remplacer `captureGroupReview()` par `confluenceBuffer.accumulate()` |
| `AlertService.java` | Ajouter kill zone check pour 5m : `if ("5m".equals(tf) && !isKillZone()) continue;` |
| `AlertService.java` | Conserver h4Snap comme filtre passif pour signaux 1H |
| `MentorSignalReviewService.java` | Nouvelle méthode `captureConsolidatedReview()` |
| `MentorSignalReviewService.java:buildPayload()` | Ajouter `confluence_signals`, `confluence_weight`, `opposing_buffer_weight` |
| `MentorSignalReviewService.java` | Semantic dedup sur quadruplet `(instrument, tf, direction, primary)` |
| `GeminiMentorClient.java` | Ajouter RÈGLE CONFLUENCE au prompt Niveau 1 |
| `application.properties` | Ajouter configs fenêtre + poids |

---

## 6. Cas d'usage

### CU-1 : Confluence forte — flush immédiat
```
T+0s:  RSI SHORT (0.5)  → buffer[MCL/10m/SHORT] poids=0.5
T+6s:  WT SHORT (2.0)   → buffer poids=2.5
T+30s: OB SHORT (3.0)   → buffer poids=5.5 → FLUSH IMMÉDIAT
→ Gemini: confluence_strength=3, weight=5.5, primary=ORDER_BLOCK
```

### CU-2 : Signal isolé — pas de review
```
T+0s:   RSI SHORT (0.5) → buffer[MCL/10m/SHORT] poids=0.5
T+120s: fenêtre expire → poids 0.5 < 3.0 → PAS DE REVIEW, loggé
```

### CU-3 : OB seul — flush immédiat
```
T+0s: OB SHORT (3.0) → buffer[MCL/10m/SHORT] poids=3.0 → FLUSH IMMÉDIAT
→ Gemini: confluence_strength=1, weight=3.0, primary=ORDER_BLOCK
```

### CU-4 : Non-cumul famille Momentum
```
T+0s:  EMA Golden Cross (1.0, famille=Momentum)  → buffer poids=1.0
T+10s: MACD Bullish Cross (1.0, famille=Momentum) → famille déjà comptée → poids=1.0 (PAS 2.0)
T+20s: WT Bullish Cross (2.0) → buffer poids=3.0 → FLUSH IMMÉDIAT
→ Gemini: 3 signaux mais weight=3.0 (non-cumul respecté)
```

### CU-5 : Deux buffers opposés flushent
```
T+0s:  OB SHORT (3.0) → buffer[MCL/10m/SHORT] poids=3.0 → FLUSH IMMÉDIAT
T+15s: OB LONG (3.0)  → buffer[MCL/10m/LONG] poids=3.0 → FLUSH IMMÉDIAT
→ Deux reviews Gemini envoyées, chacune avec opposing_buffer_weight de l'autre
```

### CU-6 : 5m hors kill zone — ignoré
```
T+0s: il est 14:00 ET (hors kill zone)
Signal RSI SHORT MCL/5m → IGNORÉ (pas d'évaluation 5m)
```

---

## 7. Critères d'acceptation

- [ ] **AC-1** : RSI + WT + OB SHORT MCL 10m en <120s → une seule review avec `confluence_weight=5.5`
- [ ] **AC-2** : RSI SHORT isolé → poids 0.5 < 3.0 → PAS de review, signal loggé
- [ ] **AC-3** : OB SHORT seul → poids 3.0 → flush immédiat, review envoyée
- [ ] **AC-4** : SHORT + LONG >= 3.0 chacun → deux reviews envoyées
- [ ] **AC-5** : EMA + MACD (même famille Momentum) → poids total = 1.0, pas 2.0
- [ ] **AC-6** : Chaikin + Delta Flow (même famille Flow) → poids total = 1.0, pas 2.0
- [ ] **AC-7** : Le primary est ORDER_BLOCK même si RSI est arrivé en premier
- [ ] **AC-8** : Payload Gemini contient `confluence_signals`, `confluence_weight`, `opposing_buffer_weight`
- [ ] **AC-9** : Semantic dedup sur quadruplet — un CHoCH SHORT après un OB SHORT peut créer une review distincte
- [ ] **AC-10** : 5m ignoré hors kill zones ICT
- [ ] **AC-11** : 4H trend utilisé comme filtre passif pour les signaux 1H
- [ ] **AC-12** : Buffer flush à expiration fenêtre fixe (pas de glissement)
- [ ] **AC-13** : Signaux sous le seuil loggés dans `signal_buffer_log` pour backtest
- [ ] **AC-14** : Thread safety via `ConcurrentHashMap.compute()` — pas de race condition
