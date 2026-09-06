# F15 livrée et revue, ni recettée ni fusionnée — état pour la reprise

Session du 6 septembre 2026. **F15 — la propagation d'une réattribution jusqu'à l'ERP** est
complète sur sa branche : dix-huit commits, onze tâches, chacune revue, plus une revue finale
de branche et sa vague de correctifs. Il reste **la recette à l'écran et la fusion**, qui
reviennent toutes deux à l'utilisateur.

## L'état du dépôt

| | |
| --- | --- |
| Branche | `feature/f15-propagation-reattribution-erp`, **non poussée** |
| `main` | inchangée, `2d4718a` |
| Arbre de travail | propre |
| Migrations | **treize** (`V13__crm_sync_attempt_nature.sql`) |
| Suites | backend **580 tests**, frontend **84** ; ESLint et Prettier verts, build production OK |
| Consommateurs | **sept** files avec un consommateur chacune |
| Features livrées | **quinze** ; onze écrans |

## Ce que F15 fait, et pourquoi ce n'est pas ce que la feuille de route annonçait

La feuille de route du 29 août réservait cette dernière session à la **refonte du pivot en carte
de références**, sur ce constat : « `CrmSyncState` est un triplet, or Dolibarr a quatre étapes et
la quatrième n'a nulle part où se ranger. » **F11.2 avait déjà rendu ce constat faux** —
`assigneeRef` est une quatrième référence de plein droit depuis, et le rejeu répare. Le
`CLAUDE.md` de `crm/` avait acté la conclusion inverse de la feuille de route, et il avait
raison contre elle.

La refonte est donc **écartée, avec ses raisons consignées dans la spec**, et F15 traite à la
place ce que la feuille de route disait que la refonte « ouvrirait », et qui n'en dépendait
pas : jusqu'ici, réattribuer un lead corrigeait LeadFlow mais pas l'ERP, et l'écran l'avouait.

Le geste, désormais : `ReattributionService` publie `lead.reassigned` après son commit et après
le journal ; une file dédiée porte le message à `CrmReassignService`, qui reconstruit l'état
antérieur et n'appelle l'ERP que s'il connaît déjà ce lead ; le port `CrmConnector` a une
troisième méthode obligatoire, `reaffecte`, que Dolibarr honore en reliant le responsable au
projet et Odoo en écrivant `user_id` sur le `crm.lead`.

## Les deux défauts que les revues ont trouvés, et qui ne se voyaient pas depuis une tâche

**L'analytique de F13 aurait menti.** Les traces de réaffectation s'écrivent en `SUCCESS`, et
`SeriesRepository` comme `SyncActivityRepository` comptaient toute ligne `SUCCESS` comme une
synchronisation réussie. Un lead jamais poussé vers l'ERP aurait donc reçu une date de
synchronisation, serait entré dans la médiane des délais et dans le compteur de succès de
l'écran Connecteurs. Les deux requêtes filtrent désormais sur la nature. La règle « la nature ne
filtre jamais une requête à elle seule », écrite au plan avant que ces trois méthodes de trace
n'existent, est caduque. `SyncActivityRepository` n'avait aucune classe de test ; elle en a une.

**Deux oublis de câblage, trouvés par la seule revue de branche entière.** La nouvelle file était
absente de la liste en dur de `QueueService` — et celle de la notification l'était depuis F12 —,
si bien qu'un consommateur tombé était indétectable depuis l'écran « Files ». Et
`DeadLetterListener.fileDOrigine` ne connaissait ni `lead.reassigned` ni `lead.synced`, rangeant
ces morts sous « inconnue », hors du filtre par file — précisément ce que le dialogue de
réattribution promet à l'opérateur.

## Une affirmation à éprouver, pas à croire

Le Javadoc affirmait qu'un rejeu de propagation est inoffensif, « poser un responsable étant
idempotent ». **C'est vrai pour Odoo** — un `write` sur `user_id` — et **non prouvé pour
Dolibarr** : aucune sonde ne permet de lui demander si un responsable est déjà lié, et
`DolibarrConnector` documente honnêtement qu'un doublon refusé enverrait en DLQ un lead par
ailleurs correctement synchronisé. La livraison étant at-least-once, ce n'est pas un cas
d'école. Les Javadoc portent désormais la réserve ; **c'est le point n°2 de la recette**.

## Le compte des consommateurs était faux avant F15

`CLAUDE.md` annonçait « cinq consommateurs » et « cinq files avec un consommateur chacune ». Le
compte réel est **sept** — sept `@RabbitListener`, sept propriétés d'extinction dans les
propriétés de test. Le document était déjà faux avant cette feature ; il est corrigé.

## La recette à mener, par ordre de priorité

L'infrastructure n'a pas été arrêtée. Docker Desktop a été démarré pendant la session.

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8090
cd frontend && npm start                                             # :4200
```

1. **L'étage `@Tag("erp")` avec vérification visuelle dans l'ERP.** C'est le seul chemin de la
   feature jamais exécuté — il demande de vrais conteneurs Dolibarr et Odoo
   (`docs/erp-integration-setup.md`, puis `./mvnw verify -Perp-it`). Les tests écrits n'assertent
   que l'absence d'exception : **ouvrir la fiche dans les deux ERP** pour lire le responsable.
2. **Le rejeu d'une propagation, deux fois.** Éteindre l'ERP, réattribuer, laisser mourir,
   rallumer, rejouer depuis le journal des morts — puis rejouer **une seconde fois** le même
   message. Si Dolibarr refuse le doublon de `lieResponsable`, l'affirmation d'idempotence tombe
   et il faudra tolérer sa réponse de doublon comme un succès.
3. **Deux morts sur un même lead.** `uq_dead_letter_lead_pending` est un index unique partiel sur
   `lead_id WHERE status = 'PENDING'` : un lead dont la synchronisation échoue déjà, puis qu'on
   réattribue avec le même ERP éteint, verrait sa seconde mort acquittée comme un doublon et
   absente du journal. La trace `crm_sync_attempt` reste, donc la chronologie ne ment pas — mais
   le dialogue, si. À constater pour décider si cela mérite une tâche.
4. **La chronologie d'un lead réattribué trois fois**, dont une propagation en échec et une sans
   effet : vérifier l'ordre des entrées et que « Responsable corrigé » ne se confond jamais avec
   « Synchronisation ERP ».
5. **Une boutique sans ERP** : le dialogue ne doit rien promettre, le geste doit réussir, et une
   ligne « connecteur inconnu » apparaît dans la chronologie — à trancher si elle est acceptable.
6. **Les sept files** : `docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages
   consumers`.

## Après la recette

```bash
git push -u origin feature/f15-propagation-reattribution-erp   # la CI ecoute feature/**
git checkout main && git merge --no-ff feature/f15-propagation-reattribution-erp && git push
```

**Ne pas supprimer la branche.** Le job `fumee` de la CI est celui qui compte ici : il monte la
pile de production complète, donc **`V13` sera appliquée sur une base vierge** — la seule preuve
qu'aucun lancement local ne peut donner, la base de développement étant persistante.

## Les dettes, triées par la revue finale

**À payer en premier à la prochaine session :** `SeriesRepositoryTest` nettoie quatre tables
entières par `deleteAll()` non porté. C'est le motif exact qui a fait tomber la CI en F14, de
façon invisible en lancement isolé parce que l'échec dépendait de l'ordre d'exécution. Antérieur
à F15, mais la feature étend cette classe.

**À laisser telle quelle :** la garde « opportunité inconnue » dupliquée dans les deux
adaptateurs. La remonter dans le service supposerait que tout ERP rattache le responsable à
l'opportunité, ce que le Javadoc du port refuse explicitement.

**Différées :** pas de filet de republication pour `lead.reassigned` — même dette assumée que
sur `lead.qualified` et `lead.routed`, et elle se paierait pour les trois clés d'un geste ; le
double de test `ServiceDeTest`, sous-classe à dépendances nulles là où un mock serait insensible
à une future validation d'arguments ; le test « réaffectation sans effet » qui n'asserte pas son
`outcome()` ; et `dernierMessageDEchec` de l'écran Connecteurs, désormais borné aux
synchronisations, qui ne montre donc plus une réaffectation ratée sur le même ERP injoignable.

Restent ouvertes, hors F15 : les quatre dettes de F14, le vrai bornage de la CTE des délais de
`SeriesRepository` laissé par F13, et le tiers Dolibarr jamais cherché par email depuis F5.

## Ce qu'il reste après F15

**Plus aucune feature de la feuille de route.** Ce qui suit est de la dette ou du hors-périmètre :
le TLS et le nonce CSP, le passage multi-instance, un second canal de notification, le recalcul
rétroactif des scores. Et les comptes multi-tenant avec rôles, écartés explicitement dès la
feuille de route.
