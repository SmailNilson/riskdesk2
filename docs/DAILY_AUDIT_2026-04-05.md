# Audit Quotidien RiskDesk — 2026-04-05

**Périmètre** : Commits `762daeb..6cd6d61` (7 commits, +2145 / -783 lignes, 54 fichiers)

---

## 1. BILAN MÉTIER (Business Analyst)

### Synthèse des évolutions récentes

| Commit | Feature | Impact Métier |
|--------|---------|---------------|
| `868a073` + `6cd6d61` | **AiMentorDesk unifié** — 4 onglets (TOUT/SIGNAUX/BEHAVIOUR/MANUEL) + sourceType SIGNAL vs BEHAVIOUR | Centralisation de tous les flux IA dans une seule interface |
| `ea58cd1` | **Mentor IA v2** — payloads per-asset-class, tick data, hiérarchie décisionnelle (Structure 50% > Order Flow 30% > Momentum 20%) | Verdicts contextuels par classe d'actif (METALS ≠ ENERGY ≠ FOREX ≠ EQUITY_INDEX) |
| `ea58cd1` | **Nouveaux calculateurs domaine** — MarketRegimeDetector (4 régimes), VolumeProfileCalculator (POC/Value Area), SessionPdArrayCalculator (Premium/Discount/Equilibrium) | Enrichissement analytique structurel majeur |
| `409a5d0` | **Suppression Open Position manuelle** end-to-end | Simplification : seul le workflow Mentor + IBKR native reste |
| `53aeeaf` | **DXY trend direction** + % 24h dans DxyPanel | Contexte USD visible sans quitter RiskDesk |
| `762daeb` | **Rollover OI-based** + suppression alertes 5m | Détection automatique migration de liquidité |

### Améliorations proposées (priorité haute)

1. **Wirer tick data réels** — Infrastructure prête (`TickByTickAggregator`, `IbkrTickDataAdapter`) mais aucune souscription `reqTickByTickData("AllLast")` active. Delta réel vs CLV estimé = confiance Mentor décuplée.
2. **Souscriptions macro** — VIX, US10Y, SI manquants → champs `null` dans `MacroCorrelationSnapshot`. Hydrater pour débloquer les règles asset-class (VIX spike → rejet LONG equity, Silver leader → Catch-up Trade metals).
3. **Insérer MarketRegimeDetector + SessionPdArray dans le payload** — Calculateurs implémentés mais non appelés dans `IndicatorService.computeSnapshot()`. Mentor ne voit pas encore PREMIUM/DISCOUNT ni le régime.
4. **Surface `fallbackReason`** dans PriceUpdate + endpoint `/api/market/health` — Trader doit comprendre pourquoi il voit du delay.

### Nouvelles fonctionnalités suggérées

| Feature | Valeur | Effort |
|---------|--------|--------|
| Multi-TF Confluence Scoring (1h + 4h alignment) | Rejet signals divergents HTF | M |
| Trade Outcome Simulation UI (WIN/LOSS/MISSED badges) | Feedback boucle → calibrage Mentor | M |
| Correlation Heatmap Widget (SI↔GC, E6↔DX, CL↔DX) | Conscience dépendances arbitrage/hedging | M |
| Catalyst Awareness — EIA Calendar Integration | Rejet ENERGY setup si EIA dans 30m | M |
| Mentor Audit Trail (arborescence décisionnelle) | Transparence verdict | M |
| Adaptive Cooldown per Instrument (SI: 180s, CL: 120s) | Ratio signal/bruit optimisé | S |

### Roadmap proposée

- **Phase 1 (Sprint 1-2)** : Fondations données — wire tick data, subscriptions macro, insert regime+PD dans payload
- **Phase 2 (Sprint 3-4)** : UX + Feedback — outcomes UI, audit trail, correlation heatmap, IBKR health endpoint
- **Phase 3 (Sprint 5-6)** : Intelligence — multi-TF confluence, risk-adjusted SL/TP per asset class, pattern tagger
- **Phase 4 (Sprint 7)** : Analytics — Mentor vs Manual trade comparison, sector rotation widget

---

## 2. BILAN TECHNIQUE

### 2.1 Nettoyeur (Code mort / Optimisation)

| Aspect | Statut |
|--------|--------|
| Fichiers Position orphelins frontend | ✅ Nettoyés correctement |
| Backend Position endpoints | ✅ GET read-only préservé, POST/PUT supprimés |
| Imports inutilisés | ✅ Aucun détecté |
| Code commenté / TODO | ✅ Aucun détecté |
| `totalBuyVolume`/`totalSellVolume` dans TickByTickAggregator | ⚠️ **Code mort** — incrémentés mais jamais lus (`snapshot()` recalcule depuis le deque) |

### 2.2 Architecte (SOLID & Design Patterns)

**Architecture hexagonale : ✅ Conforme**
- Domain layer (AssetClass, TickAggregation, MarketRegimeDetector, VolumeProfileCalculator, SessionPdArrayCalculator) : zéro import Spring/JPA
- `TickDataPort` : interface propre dans `domain/marketdata/port/`
- Infrastructure adapters (`IbkrTickDataAdapter`, `TickByTickAggregator`) : respectent la limite

**Violations SOLID détectées :**

| Violation | Fichier | Sévérité |
|-----------|---------|----------|
| **SRP** — `MentorSignalReviewService` (1232 lignes) | Orchestration + payload build + DTO conversion + WebSocket pub | ⚠️ Moyen-Haut |
| **SRP** — `GeminiMentorClient` (381 lignes) | HTTP + schema JSON + prompts + retry + sanitization | ⚠️ Moyen |
| **OCP** — Prompts hardcodés (METALS_RULES, ENERGY_RULES…) | `GeminiMentorClient.java` static Strings | ⚠️ Moyen |

**Recommandations refactoring :**
1. Extraire `MentorPayloadBuilder` depuis `MentorSignalReviewService` (~400 lignes de `buildPayload()`)
2. Extraire `GeminiPromptBuilder` et `GeminiResponseSchemaBuilder` depuis `GeminiMentorClient`
3. Externaliser les règles asset-class en YAML/config pour respecter OCP

### 2.3 QA & Sécurité

**Tests : ✅ BUILD SUCCESS — 508 tests, 0 failures, 0 errors**

#### Bugs détectés

| # | Sévérité | Description | Fichier |
|---|----------|-------------|---------|
| 1 | **🔴 CRITIQUE** | `inferMarketSession` utilise `ZoneOffset.UTC` au lieu de `America/New_York` — **violation explicite des règles DST du projet**. Sessions décalées d'1h pendant DST → verdict Mentor incorrect. | `MentorSignalReviewService.java:1093-1105` |
| 2 | **🟠 MOYEN** | `previousCumulativeDelta` — champ `long` non-volatile modifié dans `snapshot()` (side-effect dans un getter). **Data race** si 2 threads appellent `snapshot()` simultanément. | `TickByTickAggregator.java:114` |
| 3 | **🟠 MOYEN** | `peekValue` dans VolumeProfileCalculator suppose buckets exactement alignés (`current ± step`). Arrondi `HALF_UP` peut désaligner → Value Area expansion non-optimale. | `VolumeProfileCalculator.java:97-101` |
| 4 | **🟡 FAIBLE** | `linkedMap(Object... values)` — nombre impair d'arguments → `ArrayIndexOutOfBoundsException`. | `MentorSignalReviewService.java:1219-1225` |

#### Gestion d'erreurs déficiente

| # | Sévérité | Description | Fichier |
|---|----------|-------------|---------|
| 1 | **🔴 CRITIQUE** | `toDto` catch vide — JSON Gemini corrompu → review "DONE" sans contenu, silencieusement | `MentorSignalReviewService.java:447-449` |
| 2 | **🟠 MOYEN** | `CompletableFuture.runAsync()` sans `exceptionally()` — exceptions fire-and-forget disparaissent | `MentorSignalReviewService.java:196, 256, 327` |
| 3 | **🟠 MOYEN** | `writeJson` retourne `null` si sérialisation échoue → `analysisJson` corrompu en BDD | `MentorSignalReviewService.java:1186-1192` |
| 4 | **🟡 FAIBLE** | `publish` WebSocket catch silencieux → UI jamais mise à jour | `MentorSignalReviewService.java:434-439` |

#### Tests manquants critiques

| Composant | Manque |
|-----------|--------|
| **GeminiMentorClient** | **Aucun test unitaire** — retry, sanitizeJsonText, extractText, prompts dynamiques |
| **MentorSignalReviewService** | `analyzeAndPersist` échec, rejet alerte stale (>10min), `captureBehaviourReview`, guard doublon ANALYZING |
| **TickByTickAggregator** | Tests de concurrence, side-effect `previousCumulativeDelta` |
| **VolumeProfileCalculator** | tickSize invalide, volume=0, seuil exact 5 bougies, peekValue désaligné |

#### Sécurité

- **API key Gemini** : transmise via header `x-goog-api-key` (OK), mais aucun rate limiting sur appels concurrents → risque burst `429 Too Many Requests`
- **Pas de rate limiter** sur les appels Gemini déclenchés par `CompletableFuture.runAsync`

---

## 3. ACTIONS PRIORITAIRES

### P0 — Corriger immédiatement

1. **Fix `inferMarketSession`** : remplacer `ZoneOffset.UTC` par `ZoneId.of("America/New_York")` — violation architecturale documentée, bug DST actif
2. **Fix `previousCumulativeDelta`** : déclarer `volatile` ou `AtomicLong`, éliminer side-effect dans `snapshot()`

### P1 — Sprint courant

3. Créer `GeminiMentorClientTest` (retry, sanitize, extract, prompts)
4. Tester `analyzeAndPersist` en échec (transition → `STATUS_ERROR`)
5. Fix `peekValue` → utiliser `higherEntry`/`lowerEntry` au lieu de `get(next)`
6. Ajouter `log.warn` dans les catch vides de `toDto` et `writeJson`
7. Ajouter `exceptionally()` sur les `CompletableFuture.runAsync()`

### P2 — Prochain sprint

8. Supprimer `totalBuyVolume`/`totalSellVolume` (code mort dans TickByTickAggregator)
9. Extraire `MentorPayloadBuilder` depuis `MentorSignalReviewService`
10. Ajouter rate limiter (Semaphore) pour appels Gemini concurrents
