# F15 — La propagation d'une réattribution jusqu'à l'ERP

_Design, 6 septembre 2026. Dernière feature de la feuille de route. Il fixe le périmètre et
les décisions structurantes ; il ne remplace pas le plan d'implémentation._

## Pourquoi cette feature n'est pas celle que la feuille de route annonçait

Le document du 29 août réservait la dernière session à la **refonte du pivot en carte de
références**, sur ce constat : « `CrmSyncState` est un triplet de champs nommés, or Dolibarr a
quatre étapes et la quatrième n'a nulle part où se ranger. Au rejeu, le bloc gardé par
`opportunite == null` est sauté en entier, rattachement du responsable compris. »

**Ce constat a cessé d'être vrai en F11.2.** `CrmSyncState` est un quadruplet, `assigneeRef`
porte la quatrième référence au même titre que les trois autres, `lieResponsable` est une étape
à part entière tentée tant qu'elle n'est pas faite, et le rejeu répare. Le `CLAUDE.md` de
`crm/` a acté la conclusion inverse de la feuille de route, et il a raison contre elle : « une
carte de références par étape, plutôt qu'un quatrième champ, ne redeviendra la bonne réponse
que si un ERP apporte un jour une cinquième étape. » Aucun ERP n'en apporte : Dolibarr en a
quatre, Odoo trois.

La refonte est donc **écartée, délibérément et non par oubli**. Elle coûterait une colonne
`jsonb`, la reprise de l'historique de `crm_sync_attempt`, une fusion de cartes en lieu et place
de `etatAnterieur`, plus `monitoring/dto` et le frontend — pour aucun comportement nouveau à
l'écran et une généralisation dont rien n'a besoin aujourd'hui. Le jour où un cinquième
maillon apparaîtra, ce document et le Javadoc de `DolibarrConnector` diront quoi faire.

Ce que F15 traite à la place est ce que la feuille de route disait que la refonte
« ouvrirait », et qui n'en dépendait pas : **une réattribution corrige LeadFlow, pas l'ERP.**
Un lead déjà `SYNCED` garde son ancien responsable chez Dolibarr ou Odoo, et depuis F10
l'écran l'avoue à l'opérateur plutôt que de le laisser croire à une correction qui n'a pas
lieu. C'est le manque le plus visible qui reste, et le seul qui demande d'étendre le port
`CrmConnector`.

## Le flux

`ReattributionService` publie, **après** l'écriture et après le journal, un
`LeadReassignedMessage(leadId, clientId, previousSalesRepId, newSalesRepId, reassignedAt)` sur
l'exchange `leadflow.leads` avec la clé **`lead.reassigned`**. Nouvelle file
`leadflow.leads.reassigned`, DLX vers la DLQ existante, trois tentatives puis mort comme les
autres. Le consommateur `crm/CrmReassignListener` est un bean conditionnel
(`leadflow.crm.reassign.listener.enabled`) retiré dans la suite de tests comme les cinq
autres, et ne fait que traduire le protocole : le métier vit dans `CrmReassignService`.

**Asynchrone, et pas un appel dans le `PUT`.** Un ERP lent ferait traîner un geste déjà réussi
côté LeadFlow, et un ERP éteint forcerait à choisir entre échouer le geste entier ou rendre
`200` avec un avertissement — exactement le réflexe que le pipeline entier a été construit pour
éviter. Derrière la file, un Dolibarr à l'arrêt n'empêche plus l'opérateur de corriger, et la
reprise sur échec est celle de tout le projet : trois tentatives, DLQ, journal des morts,
rejeu.

**La publication est un appel direct après le commit**, sur le modèle de
`QualifiedLeadPublisher` et non un `@TransactionalEventListener` — `ReattributionService`
n'étant pas transactionnel, l'écouteur serait silencieusement ignoré. Elle part après le
journal : l'ordre inverse annoncerait à l'ERP un changement dont la trace peut encore manquer.
Le record vit dans `routing/`, déjà déclaré dans `RabbitMQConfig.PAQUETS_DE_CONFIANCE`.

**`lead.routed` n'est pas republiée**, et c'est ce qui préserve la décision de F10 : le tour de
rôle n'est pas idempotent, republier le décalerait et renverrait vers l'ERP un lead déjà
synchronisé en entier. La nouvelle clé ne transporte qu'un changement de responsable.

**Rejeu et journal des morts fonctionnent sans une ligne de code.**
`DeadLetterReplayService` republie les octets d'origine sur la clé d'origine, et
`DeadLetterListener` tire le `leadId` de n'importe quel corps qui en porte un. Poser un
responsable est par ailleurs naturellement idempotent, contrairement au tour de rôle : rejouer
une propagation est inoffensif, et l'écran n'a pas à en avertir comme il avertit pour
`lead.qualified`.

## La condition de propagation vit dans `crm/`, jamais dans `routing/`

`routing/` publie **toujours**, sans savoir si l'ERP connaît ce lead. Lui faire consulter
`crm_sync_attempt` avant de publier le couplerait à l'état CRM et lui ferait porter une
connaissance qui appartient à l'autre étape.

Un mot sur l'ordre des contrôles, parce qu'il n'est pas indifférent : le service vérifie
`registry.availableProviders()` **avant** d'appeler `forProvider`. Ce dernier lève une
`IllegalArgumentException` sur un connecteur désactivé, et une exception non attrapée ferait
partir en DLQ un cas dont ce design dit qu'il doit acquitter. Interroger la liste plutôt que
rattraper l'exception garde la décision lisible à l'endroit où elle se prend.

C'est `CrmReassignService` qui reconstruit l'état antérieur par `etatAnterieur` et n'appelle
l'ERP que s'il existe une référence d'opportunité. **Trois cas acquittent sans partir en DLQ**,
avec une trace explicite — même parti que `IGNOREE` en notification et `DISCARDED` en
qualification, parce qu'aucune répétition ne les répare :

1. **Lead jamais synchronisé.** Rien à corriger, et sa synchronisation future portera déjà le
   bon commercial : `CrmSyncService.versPivot` lit `assignedSalesRepId` au moment de l'appel.
2. **Commercial que l'ERP ne connaît pas.** `resolveAssignee` rend `null` ; il n'y a aucun
   identifiant à poser.
3. **Connecteur désactivé pour cette boutique.** Le registre refuse déjà de le résoudre.

Seul un échec technique — ERP injoignable, refus, identifiants invalides — mérite les trois
tentatives puis la DLQ.

## Le port

```java
void reaffecte(CrmSyncState references, String assigneeRef, CrmTarget cible);
```

**Obligatoire, sans implémentation par défaut**, selon le précédent posé par `verifieAcces` en
F7 : un `default` qui ne ferait rien laisserait un futur adaptateur dégrader en silence une
promesse désormais faite à l'écran. Un ERP incapable de réaffecter doit le déclarer, pas
l'omettre.

**Pas de `CrmLead` en paramètre.** La réaffectation ne touche aucune donnée du prospect ;
passer le lead entier inviterait un adaptateur à en profiter pour mettre autre chose à jour, et
la méthode cesserait d'être ce que son nom annonce. **Retour `void`** : le nouveau
`assigneeRef` est celui qu'on vient de passer, il n'y a rien à apprendre de l'ERP. L'échec est
une `CrmSyncException`, comme partout ailleurs dans le port.

Le paramètre est `CrmSyncState` et non un `String opportunityRef` : c'est déjà le vocabulaire
du port pour « ce que l'ERP connaît de ce lead », et un adaptateur qui rattacherait le
responsable ailleurs qu'à l'opportunité — au compte, par exemple — y trouve la référence dont
il a besoin sans que le contrat change.

**Dolibarr** réutilise `lieResponsable(cible, references.opportunityRef(), assigneeRef)`, écrit
en F11.2. Aucun nouvel appel HTTP à concevoir.

**Odoo demande le seul vrai travail neuf de la feature.** `OdooClient` ne sait que créer — il
expose `authentifie`, `cree`, `chercheUtilisateurParEmail` et `verifieAcces`, aucune méthode de
mise à jour. Il faut lui ajouter un `ecrit(...)` : un `object.execute_kw` avec la méthode
`write` sur `crm.lead`, posant `user_id`. Symétrique de `cree`, même transport JSON-RPC, même
authentification.

## La trace — migration `V13`

`crm_sync_attempt` gagne une colonne `nature VARCHAR(20) NOT NULL DEFAULT 'SYNCHRONISATION'`.
Contrairement au `routed_at` de `V7`, laissé nullable et sans remplissage rétroactif parce
qu'aucune valeur n'aurait été vraie, **le défaut dit ici la vérité** : toutes les lignes
existantes sont bel et bien des synchronisations.

Une réaffectation réussie écrit une ligne `REAFFECTATION` / `SUCCESS` portant `assignee_ref` =
le nouveau responsable. **`etatAnterieur` la lit sans aucune modification**, sa règle étant déjà
« la valeur non nulle la plus récente, champ par champ ». C'est la raison qui a fait choisir
cette table plutôt que `lead_action` : sans cette ligne, une resynchronisation ultérieure
relirait l'ancien responsable depuis la vieille tentative et **rétablirait chez l'ERP ce que la
réattribution venait de corriger**. Aucun autre emplacement de trace n'évitait ce bug.

Un échec écrit `REAFFECTATION` / `FAILED` avec son motif, en `REQUIRES_NEW` avant que
l'exception ne parte — précédent de `CrmSyncTraceWriter` et de `LeadActionJournal` : sans cela
la trace disparaîtrait avec le rollback, au moment précis où elle est la plus utile.

## Ce que l'écran montre

`SyncAttemptView` gagne `nature` et **`assigneeRef`**, et perd **`taskRef`**. Cette colonne
existe depuis `V2`, aucun adaptateur ne l'a jamais remplie, et l'écran de détail affiche donc
« tâche — » sur chaque tentative de chaque lead depuis F5, tout en taisant la seule référence
qui a bougé depuis. C'est le mensonge le plus visible du seul DTO que la feature touche de
toute façon ; le laisser en place pour rester « dans le périmètre » serait de la discipline mal
placée. La colonne de base reste, elle : elle ne coûte rien et un adaptateur pourra la remplir.

La chronologie lit déjà `crm_sync_attempt`. Une entrée de nature `REAFFECTATION` ajoute une
valeur à l'énumération de `LeadTimelineService`, donc **le `switch` d'expression sans branche
par défaut de `lead-timeline.ts` cessera de compiler** tant que le libellé français n'est pas
écrit. Le garde-fou posé par F9 joue exactement son rôle, et il n'y a rien à ajouter pour cela.

Le dialogue de réattribution perd sa phrase d'aveu. Pour un lead synchronisé, il annonce que la
correction sera transmise à l'ERP ; pour les autres, il ne promet rien — la promesse serait
fausse, et un lead non synchronisé n'a rien à corriger.

**Décision explicite : `lead.reassigned` n'est pas ajoutée aux liaisons de
`leadflow.monitoring.events`.** Le flux temps réel montre les leads qui avancent dans le
pipeline ; une correction manuelle n'en est pas une, et l'opérateur qui la déclenche est déjà
devant la fiche du lead.

## Tests

- **Contractuels**, sur `MockRestServiceServer`, comme tout l'étage adaptateur : le corps exact
  du `write` Odoo — pas seulement son code retour —, et l'appel `lieResponsable` de Dolibarr.
- **`CrmReassignService`** : les trois cas d'acquittement sans DLQ, chacun laissant sa trace, et
  le cas technique qui laisse partir l'exception après avoir écrit `FAILED`.
- **Le test qui compte** : après une ligne `REAFFECTATION`, `etatAnterieur` rend le **nouveau**
  responsable. C'est l'assertion qui protège contre le retour silencieux de l'ancien.
- **De bout en bout** : réattribution → message publié → ligne de trace, listener activé pour ce
  test seul.
- **Étage `@Tag("erp")`** pour les vrais conteneurs, exclu de `./mvnw verify` comme le reste.

## Ce que F15 ne fait pas

- **Pas de carte de références.** Écarté, avec ses raisons, en tête de ce document.
- **Rien d'autre que le responsable n'est propagé.** Un lead dont le score ou l'intention
  changerait après coup ne remonterait pas ; personne ne le demande, et le port resterait à
  étendre.
- **Pas de filet de republication pour `lead.reassigned`.** Si la publication échoue après le
  commit, la réattribution est faite et l'ERP ne le saura pas. C'est la même dette assumée que
  sur `lead.qualified` et `lead.routed`, nommée plutôt que masquée — et elle se paierait pour
  les trois clés d'un coup, pas pour celle-ci seule.
- **Pas de reprise de l'historique.** Les leads réattribués avant F15 gardent leur ancien
  responsable chez l'ERP ; rien ne les rattrape rétroactivement. Une nouvelle réattribution du
  même lead, elle, se propagera.

## Invariants que cette feature frôle

- **Le modèle pivot ne contient aucun terme propre à un fournisseur.** `reaffecte` parle de
  références et de responsable ; « projet », « chef de projet », `fk_user_resp`, `crm.lead` et
  `user_id` restent dans les adaptateurs.
- **Un adaptateur ne lit jamais la base.** `CrmReassignService` reconstruit l'état antérieur et
  le lui passe, comme `CrmSyncService` le fait déjà.
- **Ajouter un ERP reste trois gestes**, et la méthode obligatoire ne l'alourdit pas : elle est
  le troisième point du contrat que l'adaptateur remplit de toute façon.
- **Hibernate ne crée jamais de table** : `V13` est une migration Flyway numérotée.
- **Aucune entité JPA ne franchit la frontière HTTP** : `nature` et `assigneeRef` arrivent par
  `SyncAttemptView`.
