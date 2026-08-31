# État du dépôt à l'arrêt de la session du 31 août 2026

Ce document dit où en est le dépôt, ce que la session a livré, et ce qu'il reste à faire —
avec une estimation de durée par feature. Le design et le plan de F11.3 vivent dans leurs
propres fichiers ; celui-ci ne les répète pas.

## L'état du dépôt

| | |
| --- | --- |
| Branche courante | `feature/f11.3-integration-continue` |
| Dernier commit | `694439a` (le plan d'implémentation de F11.3) |
| Arbre de travail | propre |
| **Poussé ?** | `main` **oui**, à jour avec `origin/main`. La branche F11.3 **non** : 2 commits locaux |
| Branches conservées | toutes, y compris `fix/f11.2-polices-locales` créée aujourd'hui |

## Ce que la session a livré

**Deux défauts de F11.2 trouvés à la recette et corrigés**, fusionnés dans `main` par le
merge `359c898` et poussés.

Les deux effaçaient toutes les icônes du dashboard, et **aucun outil existant ne les
voyait** : 44 tests frontend verts, `200` sur la page, `200` sur la feuille de style.

1. **`font-src 'self'` bloquait les polices servies par Google**, dont Material Symbols
   Outlined — une police à ligatures, donc son blocage ne dégrade pas les icônes : il les
   fait disparaître. Les onze sous-ensembles `latin` et `latin-ext` sont désormais
   versionnés sous `frontend/src/fonts/`, régénérables par `python build-fonts.py`.
2. **`script-src 'self'` bloquait le `onload`** qui bascule la feuille principale de
   `media="print"` à `media="all"`. Elle n'a donc **jamais été appliquée à l'écran** depuis
   F11.2. `inlineCritical` passe à `false` en production.

Mesuré dans le navigateur avant correctif : `font-family` calculée `Arial` ou
`"Times New Roman"` sur les `mat-icon`, ligatures inactives. Basculer le `media` à `all`
depuis la console faisait apparaître les icônes — la causalité a été éprouvée, pas déduite.

**Deux étapes ajoutées au script de fumée**, `3c` et `3d`, dont les chemins d'échec ont été
éprouvés un par un : une police Google réinjectée fait échouer, une police absente aussi, un
gestionnaire inline aussi.

**F11.3 est conçue et planifiée**, pas commencée : le design (`63c14bb`) et le plan en cinq
tâches (`694439a`) sont écrits, aucun fichier de CI n'existe encore.

## Par quoi reprendre

**1. Pousser la branche F11.3.**

```bash
git push -u origin feature/f11.3-integration-continue
```

**2. Éprouver la pile de production.** Toujours pas fait, et c'est le seul point que je ne
peux pas faire : le script exige `SMOKE_PASSWORD`, le mot de passe en clair de l'opérateur,
qui n'existe nulle part sur le disque. **La pile est restée debout** à l'arrêt de la session,
donc il n'y a que le script à lancer.

```bash
SMOKE_PASSWORD='...' ./scripts/smoke-prod.sh
```

**3. La recette de F8**, due depuis trois sessions : baisser le seuil d'une boutique,
recharger la liste, et voir les pastilles « chaud » se déplacer **sans qu'aucun score ne
change**.

**4. Exécuter le plan de F11.3**, `docs/superpowers/plans/2026-08-31-f11.3-integration-continue.md`.

## Les features restantes, et leur durée

Les estimations viennent de la feuille de route
(`2026-08-29-feuille-de-route-f8-f14-design.md`), corrigées de ce que les sessions écoulées
ont appris.

| Feature | Ce qu'elle livre | Durée | Migration |
| --- | --- | --- | --- |
| **F11.3** | CI GitHub Actions, ESLint, Prettier | **1 session** | aucune |
| **F9** | Timeline dérivée du lead | 1 session | aucune |
| **F10** | Réattribution manuelle et journal d'actions | 1 session | **V7** |
| **F12** | Analytics et graphiques | 1 à 2 sessions | index |
| **F13** | Rotation HMAC à fenêtre de transition | 1 session courte | oui |
| **F14** | Limitation du pivot ERP | 1 session | oui |

**Total : cinq à sept sessions** après F11.3.

L'ordre reste **F11.3 → F9 → F10 → F12 → F13 → F14**, et le raisonnement tient en trois
points : F9 avant F10 parce que la timeline donne à la réattribution un endroit où
s'afficher ; F10 avant F12 parce que la table d'audit de F10 devient une source d'agrégats ;
F13 et F14 ferment la marche, indépendants du reste et déplaçables sans rien casser.

**F11.3 est plus courte qu'annoncée.** La feuille de route donnait « 2 à 3 sessions » pour
F11 entier ; F11.1 et F11.2 en ont pris deux, et le plan de F11.3 tient en cinq tâches dont
la plus longue est une boucle de correction sur des exécutions distantes. Une session suffit,
à condition que la lecture des résultats soit fluide — voir la note sur `gh` ci-dessous.

## Deux corrections à apporter à la feuille de route

**F10 ne portera pas une migration `V6` mais `V7`.** La feuille de route a été écrite avant
F11.2, qui a consommé `V6` (`V6__crm_sync_attempt_assignee.sql`). Le numéro annoncé pour F10
est donc faux d'un cran ; le corriger au moment du brainstorming de F10 évitera un
Flyway qui refuse de démarrer.

**F11.3 comporte une décision qui n'était pas dans la feuille de route** : le déclenchement
de l'étage de fumée sur les branches `feature/**` et `fix/**`. Elle vient d'un fait que le
document ne connaissait pas — les fusions se font localement en `--no-ff`, donc une CI
déclenchée sur les seules pull requests ne parlerait jamais.

## L'état de la machine

- **La pile de production tourne encore** : `leadflow-prod-web`, `-backend`, `-rabbitmq`,
  `-postgres`, tous sains, un seul port publié (`8088`). Elle contient les quatre leads de
  démonstration visibles au dashboard. `docker compose --env-file .env.prod -f
  docker-compose.prod.yml down` l'arrête sans effacer les volumes ; ajouter `-v` les efface.
- **Docker Desktop tourne.** L'arrêter libère environ 1,9 Go, ce qui compte sur cette machine
  (7,87 Go au total).
- **Zéro conteneur Testcontainers orphelin.**
- **Rejouer la suite backend par lots, jamais d'un bloc.** Un run complet n'a jamais survécu
  sur cette machine.

## Trois dettes qui traversent les sessions

**`gh` n'est pas installé**, et les trois dernières tâches du plan de F11.3 se terminent
chacune par la lecture d'une exécution GitHub. La lecture par navigateur fonctionne, mais
`winget install --id GitHub.cli` rendrait la boucle de correction nettement plus rapide.

**Le push mobile est désactivé dans `/config`.** Les notifications de fin de tâche
n'atteignent donc que le terminal, alors qu'elles existent pour prévenir quand l'utilisateur
est ailleurs.

**Aucune notification n'est envoyée au commercial**, et la feuille de route F8→F14 l'assume :
aucune de ces features ne la couvre. Si c'est un besoin réel de l'agence, il faut l'ajouter à
la feuille de route — il n'y est pas.
