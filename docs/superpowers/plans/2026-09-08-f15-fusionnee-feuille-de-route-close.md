# F15 fusionnée dans `main` — la feuille de route du projet est close

Fin de session du 8 septembre 2026. Ce document **remplace**
`2026-09-07-f15-recette-menee-une-decision-en-attente.md` : la décision qui y était en attente
a été prise et appliquée, et la feature est fusionnée.

**F15 est livrée, revue, recettée contre de vrais ERP, jugée à l'écran et fusionnée dans
`main`. Après elle, la feuille de route des features est terminée.**

## L'état du dépôt

| | |
| --- | --- |
| `main` | `1a7d274`, commit de fusion `--no-ff`, poussée |
| Branche | `feature/f15-propagation-reattribution-erp` à `c5826c9`, **conservée** |
| Arbre de travail | propre |
| CI | verte sur les quatre jobs au commit `c5826c9`, `fumee` compris |
| Migrations | treize |
| Suites | backend **586**, frontend 84, ESLint et Prettier verts |
| Consommateurs | sept files, un consommateur chacune |
| Écrans | onze |

## La décision qui était en attente, et ce qu'elle a coûté

Le troisième défaut de la recette d'hier : `lieResponsable` **ajoute** un contact au projet
Dolibarr et n'en retire aucun, si bien qu'une réattribution laissait la fiche avec **deux
`PROJECTLEADER`**, dont un étranger au lead. La promesse de F15 n'était tenue qu'à moitié.

**Décision prise : retirer l'ancien lien.** Trois raisons, dans l'ordre de poids. La feature
existe pour lever une ambiguïté sur qui traite le lead, et deux chefs de projet la déplacent au
lieu de la lever. L'historique existe déjà ailleurs et mieux — `lead_action` et
`crm_sync_attempt`, que la chronologie sait rendre — donc le garder dans l'ERP sous une forme
qui ment sur l'état courant est le pire des deux mondes. Et Odoo faisait déjà le bon geste, son
`write` sur `user_id` remplaçant la valeur : ne pas aligner Dolibarr aurait rendu le résultat de
la propagation dépendant de l'ERP, exactement ce que le port `CrmConnector` sert à éviter.

Le correctif tient dans `c5826c9`.

### Ce que la sonde a appris, et qui n'était pas devinable

Le chemin est `DELETE /projects/{id}/contact/{contactid}/{type}`. Le segment `contactid` attend
l'identifiant de l'**utilisateur**, pas le `rowid` de la ligne de liaison : le Javadoc de
Dolibarr affirme le contraire (« Row key of the contact in the array contact_ids ») et **se
trompe**, la route comparant `$contact['id']`. Relevé dans le code de l'instance, puis éprouvé.
C'est ce qui permet à `reaffecte` de passer directement `references.assigneeRef()` sans lire
d'abord les contacts du projet.

**Les deux idempotences en jeu ne sont pas de même nature**, et le rejeu s'appuie sur les deux :
reposer un lien déjà présent rend un `500` que `lieResponsable` absorbe depuis hier, tandis que
retirer un lien déjà absent rend `200` sans rien faire. Le retrait n'a donc besoin d'aucune
reconnaissance de signature d'erreur, contrairement à la pose.

### Le cas que l'implémentation a trouvé et que le plan n'avait pas vu

`reaffecte` pose le nouveau lien **puis** retire l'ancien — une panne entre les deux laisse le
bon responsable présent en plus de l'ancien, soit l'état qui précédait ce correctif ; l'ordre
inverse pourrait laisser le projet sans aucun chef.

Deux cas ne retirent rien. L'ancienne référence absente signifie que la synchronisation
d'origine n'avait lié personne. Et **l'ancienne confondue avec la nouvelle est la signature d'un
rejeu** : la trace porte alors déjà le nouveau responsable, et retirer le lien qu'on vient de
poser aurait vidé le projet de son responsable. La livraison étant at-least-once, ce cas serait
arrivé en production.

## La leçon de méthode, qui vaut au-delà de F15

Le test d'intégration de la réaffectation se contentait de vérifier qu'aucune exception ne
remontait — **et c'est précisément pour cela qu'il n'avait rien vu**. Il lit désormais les
contacts du projet chez Dolibarr et exige le nouveau responsable **seul**, avant et après un
rejeu.

C'est le deuxième test de F15 à avoir donné une fausse assurance, après celui d'Odoo qui
verrouillait la forme fausse de `executeKw`. Les deux échecs ont la même racine : une assertion
écrite d'après le plan, alignée sur le code plutôt que confrontée à un vrai serveur. **Contre un
ERP tiers, une assertion qui n'observe pas l'état final de l'ERP ne prouve rien.**

## Ce qui reste, et qui n'est plus de la feuille de route

**Aucune feature n'est planifiée.** Ce qui suit sont des dettes et des chantiers, chacun méritant
sa propre session.

En tête : `SeriesRepositoryTest` nettoie quatre tables entières par `deleteAll()` non porté — le
motif exact qui a fait tomber la CI en F14, invisible en lancement isolé.

Puis, pour F15 : l'absence de filet de republication pour `lead.reassigned`, à payer avec
`lead.qualified` et `lead.routed` d'un seul geste ; le double de test `ServiceDeTest` ; le test
« réaffectation sans effet » sans assertion d'`outcome()` ; `dernierMessageDEchec` de l'écran
Connecteurs, borné aux synchronisations.

**Un test d'intégration ERP reste rouge, et il est antérieur à F5** :
`dolibarrCreeLesTroisObjetsPuisNeLesRecreePasAuRejeu` attend un contact neuf au rejeu partiel et
reçoit le même identifiant, Dolibarr dédupliquant par courriel. `./mvnw verify -Perp-it` est
donc rouge sur ce seul test. **Sans effet sur la CI**, qui ne joue jamais ce profil.

Hors F15 : les quatre dettes de F14, le bornage de la CTE des délais de F13, le tiers Dolibarr
jamais cherché par email depuis F5.

Et les chantiers, tous écartés du périmètre : le TLS et le nonce CSP (donc `'unsafe-inline'`
reste sur `style-src`), le passage multi-instance (`PendingEventRelay` et `LimiteurDeDebit` sont
mono-instance), un second canal de notification, le recalcul rétroactif des scores, les comptes
multi-tenant avec rôles.

**Rien ne rattrape rétroactivement les leads réattribués avant F15**, et c'est définitif : il
n'existe aucune trace d'une réattribution qui n'avait alors rien à propager.

## Remonter l'environnement

Rien n'a été détruit. Les volumes des deux ERP survivent, leur configuration est conservée —
inutile de refaire `docs/erp-integration-setup.md`.

```bash
docker compose --profile dolibarr --profile odoo up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8090
cd frontend && npm start                                             # :4200
```

Contrôle qui tranche en une commande — **sept** files, un consommateur chacune :

```bash
docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages consumers
```

**Le secret HMAC de la boutique de démonstration a été tourné pendant la recette du 7**, donc la
valeur en clair de `R__demo_data.sql` n'est plus valide. En tourner un depuis l'écran des
boutiques, puis `./scripts/lead-signe.sh <cle> <secret>`.

Le lead de la recette, `SYNCED`, réattribué et propagé, est toujours en base :
`a9fed6e8-a078-1b5b-81a0-78fdf5f50001`. Dolibarr : `admin` (id 1) et `karim` (id 2), clé d'API
`cle-dolibarr-de-demo`. Odoo : base `leadflow`, `admin` / `admin`.
