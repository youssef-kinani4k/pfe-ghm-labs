# Dette n°1 — `SeriesRepositoryTest` n'efface plus que ses propres lignes

Session du 8 septembre 2026, après la fusion de F15. Aucune feature n'est planifiée : cette
session ne paie qu'une dette, celle qui était en tête de la liste du document de la veille.

## L'état du dépôt

| | |
| --- | --- |
| `main` | `14e3863`, inchangée |
| Branche | `fix/f13-nettoyage-series-repository-test` à `187ba86`, **non fusionnée, non poussée** |
| Arbre de travail | propre |
| Fichiers touchés | un seul, `backend/src/test/java/com/leadflow/monitoring/SeriesRepositoryTest.java` |

## Le défaut, et ce qui le rendait invisible

Les quatre `deleteAll()` de l'`@AfterEach` vidaient `crm_sync_attempt`, `lead`,
`raw_lead_event` et `client` **en entier**, dans la base Testcontainers que toute la suite
partage. Deux conséquences distinctes, dont aucune ne peut se voir en lancement isolé,
puisque toutes deux supposent qu'une **autre** classe ait laissé des lignes derrière elle.

La classe détruisait d'abord les données des autres. Et elle levait ensuite une violation de
contrainte dès qu'une autre classe avait laissé un geste humain : **`lead_action.lead_id`
référence `lead` sans cascade**, contrairement à `notification_attempt` et
`crm_sync_attempt`, si bien que `leads.deleteAll()` échouait sur une ligne qui ne lui
appartenait pas.

Ce second point a été **prouvé** par une classe de test jetable, écrite puis supprimée :
l'ancienne séquence lève bien `DataIntegrityViolationException` quand un `lead_action`
étranger existe, la nouvelle non. Cette preuve a aussi corrigé une hypothèse fausse du
diagnostic initial — `sales_rep` et `notification_attempt` sont en `ON DELETE CASCADE` et
n'étaient pour rien dans l'affaire.

## La réparation, et le faux départ qu'elle a coûté

**Le premier correctif élargissait le vidage global aux huit tables**, dans l'ordre des clés
étrangères, sur le modèle de `LeadTimelineServiceTest`. C'est exactement le faux départ que le
document de fin de F14 décrit : déplacer l'échec d'un cran au lieu de le supprimer, et
aggraver au passage la destruction des données des autres. Il a été annulé après relecture de
ce document.

**Le correctif retenu suit celui de F14** : la classe retient les identifiants de tout ce
qu'elle écrit — boutiques, événements, leads, tentatives — et n'efface que ceux-là, par
`deleteAllById`, dans l'ordre des références.

Corollaire à ne pas perdre : le nettoyage étant désormais porté, la base **peut** contenir les
lignes d'autres classes pendant l'exécution. `leFiltreDeBoutiqueIsoleLesInstances` est le seul
test de la classe à interroger `volumeParJour` sans filtre de boutique — la vue de l'agence —
donc le seul qu'une ligne étrangère puisse fausser. Sa fenêtre est désormais fermée au
lendemain de sa fixture au lieu de l'an 2100 : les captures des autres classes sont datées de
l'instant courant, très postérieur au 2 mars 2026. **C'est la borne, et non un vidage global,
qui isole ce test.**

## Ce qui est vérifié, et ce qui ne l'est pas

| Contrôle | Résultat |
| --- | --- |
| `SeriesRepositoryTest`, dix tests, sur le correctif final | vert |
| Classe de preuve — ancien nettoyage rouge, nouveau vert | vert |
| `./mvnw test-compile` après la dernière retouche | vert |
| Frontend : 84 tests, `build`, ESLint, Prettier | vert (aucun fichier frontend touché) |
| **`./mvnw verify` complet sur le correctif final** | **vert en CI** — run `34285181405`, les quatre jobs en succès, `Tests run: 586, Failures: 0, Errors: 0` |

Les 586 tests sont bien passés en début de session, mais sur la **première** version du
correctif, celle qui a été annulée. Le run complet sur la version finale n'a jamais abouti.

## Le blocage, qui est d'environnement et non de code

Trois lancements de `./mvnw verify` ont été **tués par manque de mémoire** — 8 Go sur ce
poste, Docker Desktop et WSL2 compris. Chaque run tué laisse derrière lui une JVM surefire
orpheline **et une paire de conteneurs Testcontainers que Ryuk ne récupère pas**, la JVM qui
tient la session étant toujours vivante. La saturation s'aggrave donc à chaque tentative : au
moment d'écrire, **44 conteneurs tournent et il reste moins de 600 Mo libres**.

Le nettoyage (`Stop-Process` sur les JVM orphelines, `docker rm -f` sur les conteneurs) a été
**refusé par le classifieur de permissions**. Il demande donc un geste de l'utilisateur.

## Pour reprendre

Deux chemins, au choix de l'utilisateur — le second est celui que la leçon de F14 désigne,
« seule la CI pouvait trancher » sur un défaut d'ordonnancement :

```bash
# 1. libérer le poste, puis relancer la suite complète
docker rm -f $(docker ps -q)
cd backend && ./mvnw verify

# 2. ou laisser la CI arbitrer les quatre jobs
git push -u origin fix/f13-nettoyage-series-repository-test
```

La branche est conservée dans les deux cas, comme toutes les branches du projet.

**Le second chemin a été pris : la branche est poussée et la CI a tranché — les quatre jobs
sont verts sur le run `34285181405`. La dette n°1 est corrigée et validée ; seule la fusion
`--no-ff` dans `main` reste à faire.** Le blocage décrit ci-dessus était bien d'environnement
et non de code, et les conteneurs orphelins du poste n'ont toujours pas été nettoyés.
