# F14 fusionnée, recettée et verte en CI — état pour la reprise

Session du 5 au 6 septembre 2026. F14 est **complète, revue, recettée à l'écran, fusionnée dans
`main` en `--no-ff` et poussée**. La CI est **verte sur ses quatre jobs**, job de fumée compris.

Ce document remplace `2026-09-05-f14-fin-de-feature.md`, écrit quelques heures plus tôt, dont le
titre annonçait « ni recettée ni fusionnée » : les deux points ouverts qu'il listait ont été
traités depuis. Le reste de son contenu — les décisions de conception, les défauts trouvés en
chemin, les quatre dettes nommées — reste exact et reste la référence.

## L'état du dépôt

| | |
| --- | --- |
| `main` | **`a5caf9f`**, poussée, synchronisée avec `origin/main` |
| CI | **verte**, quatre jobs sur quatre |
| Arbre de travail | **propre** |
| Migrations | **douze** |
| Suites | backend **548 tests**, frontend **82** ; ESLint et Prettier verts |
| Branches conservées | `feature/f14-rotation-hmac`, `fix/f14-nettoyage-du-test` |
| Features livrées | **quatorze** ; onze écrans |

Le job de fumée est celui qui compte le plus pour cette feature : il monte la pile de production
complète derrière Nginx et la traverse de bout en bout, donc **`V11` et `V12` ont été appliquées
sur une base entièrement vierge** — la seule preuve qu'aucun lancement local ne pouvait donner,
la base de développement étant persistante.

## La recette à l'écran, et ce qu'elle a trouvé

Elle a été faite au navigateur, avec les `confirm()` interceptés pour lire leur texte sans
bloquer l'extension. Les six points sont passés, y compris celui qui compte : **un lead signé
avec l'ancien secret est accepté pendant la fenêtre** (`202`, ligne marquée `signed_with_previous_secret`),
puis refusé (`401`) après révocation.

Deux défauts qu'aucun test ne pouvait attraper, corrigés dans la foulée (`dfe16b9`) :

- **Les dates sortaient en anglais américain** — « Sep 7, 2026, 12:39:19 AM » au milieu d'une
  console française. Aucun `LOCALE_ID` n'est enregistré dans l'application, et tous les autres
  écrans contournent cela en imposant un motif explicite ; les deux emplacements de F14 étaient
  les seuls à utiliser les formats nommés `'medium'` et `'short'`. Un test verrouille désormais
  le format, après avoir été vu échouer sur l'ancien.
- **Le panneau du secret promettait encore** que « le formulaire de la boutique cessera de
  fonctionner jusqu'à sa mise à jour ». C'était vrai avant F14 ; pendant une fenêtre, c'est faux,
  et cela contredisait le dialogue de rotation lu quelques secondes plus tôt. La phrase ne
  s'affiche plus que hors transition, c'est-à-dire à la création d'une boutique.

## La CI rouge, et la leçon qui vaut d'être retenue

Le premier push a fait **échouer le job backend**, deux fois de suite.

```
TransitionSecretPersistenceTest.nettoie » DataIntegrityViolation
  Key (id)=(…) is still referenced from table "raw_lead_event"
```

La revue de la tâche 1 avait signalé ce `deleteAll()` non porté et l'avait classé sans risque,
« motif déjà établi ailleurs ». Le motif existait — mais avec la suppression des captures
**avant** celle des boutiques, ce qui manquait ici.

**Le premier correctif a déplacé l'échec au lieu de le supprimer** : la CI est passée de
« `client` est référencée par `raw_lead_event` » à « `raw_lead_event` est référencée par
`lead` ». Voir une erreur remonter d'une table à l'autre était le signe que la réparation était
au mauvais niveau, et cela aurait dû arrêter avant le push plutôt qu'après.

La cause réelle : **ces trois classes vidaient des tables entières qu'elles ne possèdent pas**,
dans une base Testcontainers partagée par toute la suite. Chacune retient désormais les
identifiants des boutiques qu'elle a créées et n'efface que celles-là, plus les captures qui leur
appartiennent. C'est une classe de problème réglée, pas seulement le cas observé.

**Ce qu'un lancement local ne prouvait pas** : l'échec dépendait de l'ordre d'exécution, et ces
classes passaient déjà avant chaque correctif. Seule la CI pouvait trancher — utile à savoir pour
la prochaine fois qu'un test touche `client`, `raw_lead_event` ou `lead`.

## Un outil ajouté en passant

`scripts/lead-signe.sh <cle-publique> <secret> [email]` envoie un lead de test signé en
HMAC-SHA256 : `202` accepté, `401` refusé. C'est le seul geste de la recette que le dashboard ne
fait pas, et il resservira à toute recette touchant la capture.

## Pour reprendre demain

L'infrastructure a été arrêtée en fin de session (`docker compose down`, **sans `-v`**) : la base
de développement est conservée, avec ses leads d'essai et le secret HMAC de la boutique de
démonstration tourné plusieurs fois — la valeur en clair de `R__demo_data.sql` n'est donc
toujours pas valide.

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8090
cd frontend && npm start                                             # :4200
```

Contrôle qui tranche une fois le backend levé : `docker exec leadflow-rabbitmq rabbitmqctl
list_queues name messages consumers` doit montrer cinq files métier avec un consommateur chacune.

## La suite

- **F15 — pivot ERP en carte de références.** La dernière de la feuille de route, et la plus
  lourde : elle touche `crm/model`, les deux adaptateurs, `monitoring/dto` et le frontend, et
  ouvre la propagation d'une réattribution jusqu'à l'ERP.
- Les **quatre dettes nommées par F14**, détaillées dans `2026-09-05-f14-fin-de-feature.md` : le
  prédicat de fenêtre écrit à deux endroits, l'index qui ne porte pas le drapeau, `transitionSecret`
  non validée, et l'absence de sortie d'écran pour une fenêtre expirée.
- La dette laissée par F13, toujours ouverte : **le vrai bornage de la CTE des délais** de
  `SeriesRepository`.
- Hors feuille de route : le TLS, le nonce CSP, le passage multi-instance, un second canal de
  notification, le recalcul rétroactif des scores.
