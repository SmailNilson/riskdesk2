# TEMPLATE — Use Case ScalTrade

> Copier ce fichier et renommer : `UC-[DOMAINE]-[NNN]_titre-court.md`
> Créer la page miroir dans Notion 📚 Documentation avant d'ouvrir le PR.

---

## Identification

| Champ | Valeur |
|---|---|
| **UC-ID** | `UC-[DOMAINE]-[NNN]` |
| **Titre** | *(titre court et précis)* |
| **Domaine** | *(MKT / POS / RISK / INDICATOR / MENTOR / SIM / IBKR / PORT / ROLL / DXY / IMPORT / ALERT / EXEC / BEHAV)* |
| **Priorité** | 🔴 Critique / 🟠 Haute / 🟡 Moyenne / 🟢 Basse |
| **Sprint cible** | S__ |
| **Statut** | 🟡 Draft / 🚧 En cours / ✅ Implémenté |
| **Version** | V1 |
| **Date** | YYYY-MM-DD |

---

## Contexte & Problème

> Pourquoi ce UC existe. Quel comportement actuel est insuffisant ou manquant.
> Référence à un événement réel si applicable (ex: MCL 2026-04-02, prix 110.28).

---

## Objectif Business

> Ce que ce UC doit permettre du point de vue utilisateur / métier trading. Une phrase claire.

---

## Comportement Actuel

> Ce qui se passe aujourd'hui dans le code. Être précis sur les services, méthodes, flux concernés.
> Mettre "N/A" si c'est un nouveau use case sans comportement existant.

---

## Comportement Attendu

> Ce qui doit se passer après implémentation. Décrire le flux complet, les entrées, les sorties,
> les états intermédiaires. Numéroter les étapes.

1. Étape 1
2. Étape 2
3. ...

---

## Règles Métier

- Règle 1 — *(ex: ne fire jamais sur une bougie ouverte)*
- Règle 2 — *(ex: cooldown de 300s par clé de signal)*
- Règle 3 — ...

---

## Règles Techniques

- Contrainte 1 — *(ex: source de données uniquement IBKR Gateway → PostgreSQL)*
- Contrainte 2 — *(ex: logique métier dans domain/, pas dans application/ ni presentation/)*
- Contrainte 3 — *(ex: pas de source externe — jamais Yahoo/Polygon/CSV)*

---

## Impact Backend

**Fichiers à créer :**
```
domain/[module]/model/MonModel.java
domain/[module]/service/MonService.java
```

**Fichiers à modifier :**
```
application/service/ExistingService.java  — motif du changement
```

---

## Impact Frontend

> Composants concernés, nouveaux états, hooks modifiés. Mettre "Aucun" si pas d'impact.

---

## Impact Base de Données

> Nouvelles tables, colonnes ajoutées, index, migrations Flyway. Mettre "Aucun" si pas d'impact.

---

## Impact API / WebSocket

> Nouveaux endpoints REST, champs ajoutés aux DTOs, nouveaux topics WebSocket.
> Mettre "Aucun" si pas d'impact.

---

## Critères d'Acceptation

- [ ] CA-1 : *(comportement observable précis)*
- [ ] CA-2 : *(comportement observable précis)*
- [ ] CA-3 : *(comportement observable précis)*

---

## Scénarios de Test

> Référencer le fichier `.feature` si applicable.

| # | Scénario | Entrée | Résultat attendu |
|---|---|---|---|
| T-1 | *(cas nominal)* | | |
| T-2 | *(cas limite)* | | |
| T-3 | *(cas d'erreur)* | | |

---

## Cas Limites & Edge Cases

- Edge case 1 — *(ex: que se passe-t-il si le price est exactement sur le niveau?)*
- Edge case 2 — ...

---

## Questions Ouvertes

- [ ] Q1 : *(décision encore à prendre)*
- [ ] Q2 : ...

---

## Décision

> Résumé de la décision architecturale finale une fois le UC validé. Rempli après discussion.

---

## Liens

- UC parent / dépendance : *(lien Notion)*
- Epic lié : *(lien Notion)*
- PR / commit : *(lien GitHub)*
- Notion : *(URL page Notion)*
