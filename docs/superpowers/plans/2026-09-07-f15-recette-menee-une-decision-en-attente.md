# F15 recettée contre de vrais ERP — une décision en attente avant la fusion

Fin de session du 7 septembre 2026, tôt. Ce document **remplace**
`2026-09-06-f15-livree-non-fusionnee.md`, écrit quelques heures plus tôt : la recette y était
annoncée comme restant à faire, elle a été menée depuis. Le reste de son contenu — les
décisions de conception, le tri des dettes — reste exact.

**F15 est recettée contre de vraies instances Dolibarr et Odoo. La recette a trouvé trois
défauts, deux sont corrigés, le troisième attend une décision qui appartient à l'utilisateur.**

## L'état du dépôt

| | |
| --- | --- |
| Branche | `feature/f15-propagation-reattribution-erp`, **une avance sur `origin`** (`18b624b` non poussé) |
| `main` | inchangée, `2d4718a` |
| Arbre de travail | propre |
| CI | **verte sur les quatre jobs** au commit `886f633`, `fumee` compris |
| Migrations | treize |
| Suites | backend 580, frontend 84, ESLint et Prettier verts |
| Consommateurs | **sept** files, un consommateur chacune |

**À faire en premier demain** : `git push`. Le dernier commit n'est pas encore parti, donc la
CI n'a pas vu les correctifs de recette.

## Ce que la recette a prouvé

Le trajet complet a été éprouvé de bout en bout, par l'API, contre les vrais conteneurs :
lead signé capturé → qualifié → attribué à Amina → synchronisé sur le projet Dolibarr 28 →
réattribué à Karim depuis l'endpoint du dashboard → **message publié, consommé, et Dolibarr
modifié**.

La trace porte les deux lignes attendues — `SYNCHRONISATION / SUCCESS / assignee 1` puis
`REAFFECTATION / SUCCESS / assignee 2` — et **le lead reste `SYNCED`** : la garantie posée à la
tâche 4 tient. La chronologie rend les onze entrées dans l'ordre, `REAFFECTATION_ERP` en
dernier, et la mort de notification y apparaît rangée sous sa file `leadflow.leads.notify`,
preuve que le correctif de la revue finale fonctionne.

Ce qui **n'a pas** été fait : la passe visuelle à l'écran. L'extension Chrome n'était pas
connectée. Restent donc à juger de l'œil : le libellé « Responsable corrigé » et son icône dans
la chronologie, le bandeau du dialogue de réattribution passé de l'ambre au neutre, et la
colonne « compte » qui a remplacé « tiers » sur la fiche.

## Les trois défauts trouvés

### 1. Odoo n'a jamais pu recevoir la correction — corrigé (`18b624b`)

`OdooClient.executeKw` **étale** sa liste d'arguments dans l'appel JSON-RPC. C'est juste pour
`create`, faux pour `write` : la carte des champs devenait des arguments nommés, et Odoo
répondait `Lead.write() got an unexpected keyword argument 'user_id'`.

**Pourquoi aucun test ne l'a vu, et c'est la leçon de la session** : le test contractuel de la
tâche 5 assertait `args[5][0]` et `args[6].user_id` — il décrivait exactement la forme fausse,
donc il *verrouillait* le défaut au lieu de l'attraper. L'erreur vient du plan, qui donnait ces
index sans connaître le comportement d'`executeKw` ; l'implémenteur les a alignés sur le code,
et la revue a validé la cohérence des deux sans que rien ne confronte l'ensemble à un vrai
serveur. Les assertions décrivent désormais la forme réelle.

### 2. Dolibarr refusait un lien de responsable déjà posé — corrigé (`18b624b`)

Le premier appel réussit ; **rejouer le même lien** rend `500` avec
`Internal Server Error: Error : result :0`, source `api_projects.class.php`. C'est mot pour mot
la fenêtre résiduelle que le Javadoc de `DolibarrConnector` décrivait depuis F11.2 comme
« supposée, à éprouver contre une vraie instance ». **Elle est éprouvée.** La livraison étant
at-least-once et le rejeu depuis le journal des morts étant courant, un lead correctement
synchronisé partait en file des morts au second passage.

`lieResponsable` reconnaît désormais cette signature précise et l'absorbe comme un succès — un
lien déjà posé est l'état recherché. Tout autre `500` lève toujours. Deux tests contractuels le
verrouillent, et les trois Javadoc qui affirmaient l'idempotence sont corrigés.

### 3. Dolibarr garde l'ancien responsable — **DÉCISION EN ATTENTE**

État du projet 28 après la réattribution :

| projet | utilisateur | login | rôle |
| --- | --- | --- | --- |
| 28 | 1 | admin | PROJECTLEADER |
| 28 | 2 | karim | PROJECTLEADER |

`lieResponsable` **ajoute** un contact au projet, il n'en retire aucun. Dolibarr affiche donc
**deux chefs de projet**, dont un qui n'a plus rien à voir avec le lead. La promesse de F15
n'est tenue qu'à moitié : le bon responsable est ajouté, le mauvais reste. Odoo n'a pas ce
problème — son `write` sur `user_id` remplace la valeur.

C'est réparable et l'adaptateur a déjà ce qu'il faut : `reaffecte` reçoit l'ancien responsable
dans `references.assigneeRef()`. Il manque un appel de retrait, côté Dolibarr uniquement.

**Les deux options, à trancher demain :**

- **Retirer l'ancien lien.** Un seul chef de projet, celui qui traite le lead. C'est la
  recommandation : deux responsables sur une fiche, c'est exactement l'ambiguïté que la feature
  devait supprimer.
- **Garder les deux.** L'ERP conserve la trace de qui a été responsable, au prix d'une fiche
  ambiguë. Défendable si l'ERP est vu comme un historique.

### Et un quatrième, antérieur à F15

`dolibarrCreeLesTroisObjetsPuisNeLesRecreePasAuRejeu` échoue : le rejeu partiel attend un
contact neuf et reçoit le même identifiant, Dolibarr dédupliquant apparemment par courriel.
**Étranger à la feature** — vérifié, F15 n'a ajouté que `reaffecte` à cet adaptateur et n'a pas
touché `DolibarrClient`. `./mvnw verify -Perp-it` reste donc rouge sur ce seul test.

## Remonter l'environnement demain

Tout a été arrêté en fin de session par `docker compose down`, **sans `-v`** : les volumes
survivent, donc **la configuration des deux ERP est conservée** — inutile de refaire la mise en
route de `docs/erp-integration-setup.md`.

```bash
docker compose --profile dolibarr --profile odoo up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8090
cd frontend && npm start                                             # :4200
```

Ce qui a été mis en place pendant la recette et qui persiste :

- Dolibarr : module API actif, clé `cle-dolibarr-de-demo`, modules Tiers et Projets activés.
  **Deux utilisateurs** : `admin` (`amina@demo.test`, id 1) et `karim` (`karim@demo.test`,
  id 2) — ce second a été créé pour que les deux commerciaux de la boutique de démonstration
  existent des deux côtés.
- Odoo : base `leadflow`, module `crm` installé, `admin` / `admin`.
- **Le secret HMAC de la boutique de démonstration a été tourné pendant la recette.** La valeur
  en clair de `R__demo_data.sql` n'est donc toujours pas valide ; en tourner un nouveau depuis
  l'écran des boutiques, ou par
  `POST /api/admin/clients/{id}/rotate-secret`, puis `./scripts/lead-signe.sh <cle> <secret>`.

Contrôle qui tranche en une commande, une fois le backend levé — **sept** files, un
consommateur chacune :

```bash
docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages consumers
```

Variables pour rejouer l'étage ERP :

```bash
export LEADFLOW_DOLIBARR_URL=http://localhost:8081/api/index.php
export LEADFLOW_DOLIBARR_API_KEY=cle-dolibarr-de-demo
export LEADFLOW_ODOO_URL=http://localhost:8069
export LEADFLOW_ODOO_DB=leadflow LEADFLOW_ODOO_USER=admin LEADFLOW_ODOO_PASSWORD=admin
cd backend && ./mvnw verify -Perp-it -Dtest=ErpIntegrationTest -DfailIfNoTests=false
```

## L'ordre des choses demain

1. **Trancher le défaut n°3** — retirer l'ancien responsable côté Dolibarr, ou l'assumer.
2. **Pousser** (`18b624b` attend), et attendre les quatre jobs.
3. **La passe visuelle** à l'écran, si l'extension du navigateur est disponible.
4. **Fusionner** : `git checkout main && git merge --no-ff feature/f15-propagation-reattribution-erp && git push`. **Ne pas supprimer la branche.**
5. Écrire l'état de fin de F15 et clore la feuille de route.

## Les dettes, inchangées

**En tête de la prochaine session** : `SeriesRepositoryTest` nettoie quatre tables entières par
`deleteAll()` non porté — le motif exact qui a fait tomber la CI en F14, invisible en lancement
isolé.

Puis : la déduplication de contact Dolibarr (défaut n°4 ci-dessus, antérieur à F5) ; l'absence
de filet de republication pour `lead.reassigned`, à payer avec `lead.qualified` et `lead.routed`
d'un seul geste ; le double de test `ServiceDeTest` ; le test « réaffectation sans effet » sans
assertion d'`outcome()` ; `dernierMessageDEchec` de l'écran Connecteurs, désormais borné aux
synchronisations. Et hors F15 : les quatre dettes de F14, le bornage de la CTE des délais de
F13, le tiers Dolibarr jamais cherché par email depuis F5.

**Après F15, la feuille de route est close.** Ne restent que le TLS et le nonce CSP, le passage
multi-instance, un second canal de notification, le recalcul rétroactif des scores — et les
comptes multi-tenant avec rôles, écartés dès l'origine.
