# Fin de session — trois dettes corrigées, aucune fusionnée

Session des 8 et 9 septembre 2026, la première après la clôture de la feuille de route des
features. Aucune feature n'a été touchée : cette session n'a payé que des dettes.

## L'état du dépôt

**`main` est restée à `14e3863`. Rien n'a été fusionné, et rien ne doit l'être sans votre
validation explicite.** Trois branches poussées attendent cette décision.

| Branche | Tête | CI | Dette |
| --- | --- | --- | --- |
| `fix/f13-nettoyage-series-repository-test` | `c4e3045` | **verte, 4 jobs** | n°1 |
| `fix/f5-tiers-dolibarr-par-email` | `cba9fb8` | **verte, 4 jobs** | n°7 |
| `fix/f5-rejeu-dolibarr-converge` | `0daf991` | **verte, 4 jobs** | n°3 |

**Les deux branches Dolibarr sont empilées, pas parallèles** : `fix/f5-rejeu-dolibarr-converge`
contient `cba9fb8`. C'est délibéré — l'assertion du « rejeu total » du test d'intégration ne
tient que si la recherche de tiers par courriel existe. Les fusionner dans l'ordre, ou ne
fusionner que la seconde.

## Dette n°1 — `SeriesRepositoryTest` n'efface plus que ses propres lignes

Détaillée dans `2026-09-08-dette-1-nettoyage-series-repository-test.md`, à lire pour le
diagnostic complet. En bref : quatre `deleteAll()` vidaient des tables entières que la classe
ne possède pas, dans la base Testcontainers partagée. Elle détruisait les données des autres
classes, et levait une violation de contrainte dès qu'une autre avait laissé un `lead_action`
— cette table référence `lead` **sans cascade**, contrairement à `notification_attempt` et
`crm_sync_attempt`.

La réparation suit celle de F14 : suppression par identifiant de ce que la classe a écrit.
**Un premier correctif élargissait le vidage global aux huit tables ; il a été annulé** — c'est
exactement le faux départ que le document de fin de F14 décrit, déplacer l'échec d'un cran.

## Dette n°7 — le tiers Dolibarr se cherche par courriel

`DolibarrConnector.sync` créait le tiers sans demander à l'ERP s'il le connaissait déjà.
C'était **la seule étape de `sync` que rien ne protégeait d'un doublon** : l'opportunité l'est
par sa `ref` stable depuis F3, le contact par la déduplication que Dolibarr applique lui-même
sur le courriel. Un prospect revenant par un second formulaire ouvrait un doublon de tiers ; un
rejeu après une réponse perdue en ouvrait un troisième.

`DolibarrClient.chercheTiersParEmail` est le pendant exact de `chercheOpportuniteParRef` —
même filtre `sqlfilters`, même traitement du `404` comme une absence et non une panne. Écrit
en TDD, rouge d'abord. **44 tests contractuels verts en local** ; ces classes sont du JUnit nu,
donc exécutables sans Docker.

## Dette n°3 — et la question de la cause racine, tranchée

**Les dettes n°3 et n°7 ne partagent pas la même cause racine, et corriger la n°7 ne fait pas
passer le test.** Dans le rejeu du test, `accountRef` est fourni, donc la branche du tiers est
entièrement sautée ; les assertions qui échouaient portaient sur le contact et l'opportunité.

La vraie cause : **le test verrouillait l'inverse de la propriété recherchée.** Il exigeait
qu'un rejeu partiel rende un contact et une opportunité *neufs*. Cette attente était déjà
fausse à son écriture en F5 — `chercheOpportuniteParRef` retrouve l'opportunité depuis F3, et
Dolibarr déduplique les contacts par courriel. Le test demandait des doublons.

Il asserte désormais la convergence, et couvre en plus le **rejeu total depuis un état vierge**
— le cas de la mort livrée deux fois, qui ne devient départageable qu'avec la recherche de
tiers de la dette n°7.

### Ce qui reste non vérifié, et c'est important

**Le test de la dette n°3 n'a jamais été exécuté.** `ErpIntegrationTest` est `@Tag("erp")`,
donc exclu de `./mvnw verify` : **la CI ne le joue pas**, et sa CI verte ne prouve rien à son
sujet. La tentative de le jouer localement a échoué — `docker compose --profile dolibarr up -d`
est mort sur `unable to get image 'mariadb:11': Bad Gateway`, le démon étant alors dégradé.

Donc l'affirmation « Dolibarr déduplique les contacts par courriel » vient de la documentation
du projet et du symptôme rapporté, **pas d'une observation faite cette session**. La leçon de
F15 s'applique mot pour mot : contre un ERP tiers, une assertion qu'on n'a pas confrontée à un
vrai serveur ne prouve rien. **À jouer avant de croire ce test :**

```bash
docker compose --profile dolibarr up -d
export LEADFLOW_DOLIBARR_API_KEY=cle-dolibarr-de-demo
cd backend && ./mvnw verify -Perp-it
```

## L'état de la machine, et ce qu'il a coûté

Trois `./mvnw verify` ont été tués par manque de mémoire, chacun laissant une JVM surefire
orpheline et une paire de conteneurs Testcontainers que Ryuk ne pouvait plus récupérer. La
spirale a fini par saturer le démon Docker, qui rendait `Internal Server Error` jusque sur
`docker ps`.

Nettoyage fait en fin de session : démon redémarré, **50 conteneurs orphelins supprimés**,
quatre JVM (deux booters surefire, deux wrappers Maven) terminées. **Les six conteneurs
`leadflow-*` ont été conservés** — arrêtés, mais leur configuration ERP est rattachée à leurs
volumes, et les effacer imposerait de refaire `docs/erp-integration-setup.md`.

**Mais la marge n'a pas augmenté.** Les conteneurs orphelins étaient tous `Exited` : ils
saturaient le démon, pas la RAM. Le vrai poids est `vmmem` (3,2 Go) et le navigateur (~1,3 Go).
Sur 8 Go, **un `./mvnw verify` complet en local reste hasardeux tant que ces deux-là ne sont pas
allégés** — c'est ce qui a rendu la CI indispensable cette session, et ce qui le restera.

## Pour reprendre

1. Décider de la fusion des trois branches — dans l'ordre pour les deux branches Dolibarr.
2. Avant de croire la dette n°3, jouer `-Perp-it` contre un vrai Dolibarr (commandes ci-dessus).
3. Les dettes restantes, inchangées depuis le document du 8 : le filet de republication de
   `lead.reassigned` / `lead.qualified` / `lead.routed` (n°2), les petites dettes F15 (n°4),
   les quatre dettes de F14 (n°5), le bornage de la CTE des délais de F13 (n°6).
