# Connecteurs ERP/CRM

Ce fichier ne se charge que lorsqu'on travaille sous `backend/src/main/java/com/leadflow/crm/`.
L'invariant qui gouverne ce package — aucun ERP cable en dur, aucun terme propre a un
fournisseur dans le modele pivot — est rappele dans le `CLAUDE.md` de la racine, parce qu'il
engage tout le projet et pas seulement ce repertoire.

```
crm/
├── CrmConnector.java          port : providerId(), sync(...), resolveAssignee, reglagesAttendus, verifieAcces, reaffecte
├── CrmConnectorRegistry.java  resout l'adaptateur par providerId, applique `enabled`
├── CrmSyncService.java        orchestration : cible, etat anterieur, trace
├── CrmSyncTraceWriter.java    ecriture de la trace en transaction propre
├── CrmHttpConfig.java         builderPour(providerId) : un RestClient.Builder par ERP, avec ses delais
├── CrmSyncAttempt.java        trace append-only des synchronisations
├── model/                     modele pivot : CrmLead, CrmTarget, CrmSyncState, CrmAssignee, ...
├── dolibarr/                  adaptateur REST : DolibarrConnector + DolibarrClient
└── odoo/                      adaptateur JSON-RPC : OdooConnector + OdooClient
```

**Le modele pivot de `crm/model` ne doit contenir aucun terme propre a un fournisseur** —
ni « thirdparty » (Dolibarr), ni « res.partner » (Odoo). Toute la traduction se fait dans
l'adaptateur. C'est ce qui rend le pipeline independant de l'ERP, et c'est l'invariant le
plus facile a casser par inadvertance : des qu'un champ specifique remonte dans `CrmLead`,
la generalisation est perdue.

Les ERP ne se correspondent pas un pour un, et c'est normal : Dolibarr separe Tiers et
Contact en deux endpoints, alors qu'Odoo met les deux dans `res.partner` distingues par
`is_company`. Ces divergences se resolvent dans l'adaptateur, jamais en amont.

**Ajouter un ERP** se fait en trois gestes, sans toucher au reste du code :

1. un sous-package `crm/<provider>/` ;
2. une classe `@Component` implementant `CrmConnector`, dont `providerId()` renvoie la cle
   utilisee dans la configuration ;
3. une entree sous `leadflow.crm.providers.<provider>` dans `application.yml`.

`CrmConnectorRegistry` collecte les connecteurs par injection de `List<CrmConnector>` : il
n'y a aucune liste de fournisseurs a maintenir a la main, et aucun `switch` sur le nom de
l'ERP a ajouter quelque part.

**Le port porte deux methodes que F7 a ajoutees, et aucune n'a d'implementation par defaut.**
`reglagesAttendus()` declare les cles que l'adaptateur attend dans `crm_config`, avec leur
libelle et leur caractere secret : le formulaire d'administration se genere a partir de la,
donc ajouter un ERP ne touche ni `tenant/` ni le frontend. `verifieAcces(CrmTarget)` eprouve
une cible sans rien creer et rend un `CrmCheck`, dont la cause appartient a une enumeration
sans terme propre a un fournisseur. Un `default` rendant « non verifiable » aurait laisse un
futur adaptateur degrader silencieusement la promesse faite a l'ecran de creation — d'ou
l'obligation. La sonde ne lit pas la base, comme le reste de l'adaptateur : elle n'eprouve
que les reglages qu'on lui passe.

`sync` prend un `CrmTarget(providerId, settings)` decrivant **l'instance** ERP visee, car
deux clients sur le meme type d'ERP ont chacun leur serveur. Les cles de `settings`
viennent de `client.crm_config` et sont interpretees par l'adaptateur seul.

**Un adaptateur ne lit jamais la base.** Il recoit `CrmSyncState` — les references deja
obtenues lors des tentatives precedentes — et saute toute etape dont la reference est
connue. C'est la, et nulle part ailleurs, que se joue l'absence de doublon au rejeu. En cas
d'echec partiel, il leve une `CrmSyncException` enrichie de ce qu'il avait obtenu, sans
quoi le rejeu recreerait ce qui existe deja.

Le rattachement du responsable Dolibarr est une limitation connue, documentee dans le
Javadoc de `DolibarrConnector` : Dolibarr ignore `fk_user_resp` a la creation comme en
modification, ce qui impose un second appel. Jusqu'a F11.2, ce second appel etait imbrique
dans la garde de creation de l'opportunite ; un echec y laissait une opportunite sans chef
de projet que le rejeu sautait, sans rien signaler. Depuis F11.2, `CrmSyncState.assigneeRef`
porte cette quatrieme reference au meme titre que les trois autres, l'attribution est une
etape a part entiere tentee tant qu'elle n'est pas deja faite, et le rejeu repare. Une carte
de references par etape, plutot qu'un quatrieme champ, ne redeviendra la bonne reponse que
si un ERP apporte un jour une cinquieme etape.

**Le port porte une troisieme methode obligatoire depuis F15.** `reaffecte(references,
assigneeRef, cible)` corrige le responsable d'un lead deja present dans l'ERP, sans rien
creer. Sans `default`, pour la meme raison que `verifieAcces` : un ERP incapable de
reaffecter doit le declarer en levant, pas l'omettre. Dolibarr y rattache le responsable au
projet par `lieResponsable`, Odoo y ecrit `user_id` sur le `crm.lead` — la divergence se
resout dans l'adaptateur, comme celle du Tiers et du Contact.

**Cette divergence va plus loin qu'un nom de champ : Dolibarr demande deux appels la ou Odoo
n'en demande qu'un.** `lieResponsable` *ajoute* un contact au projet et n'en retire aucun, si
bien qu'une reaffectation laissait la fiche avec deux `PROJECTLEADER`, dont un etranger au
lead — la recette de F15 l'a observe sur une vraie instance. `reaffecte` pose donc le nouveau
lien **puis** retire l'ancien par `retireResponsable`, dans cet ordre : une panne entre les
deux laisse le bon responsable en place, l'ordre inverse pourrait laisser le projet sans chef.
Le `write` d'Odoo sur `user_id` remplacant la valeur, l'adaptateur Odoo n'a rien de tout cela.
Les deux idempotences ne sont pas de meme nature non plus, et le rejeu s'appuie sur les deux :
reposer un lien present rend un `500` que `lieResponsable` absorbe, retirer un lien absent rend
`200` sans rien faire. Le retrait est enfin saute quand l'ancien responsable est absent — rien
a defaire — ou confondu avec le nouveau, ce qui est la signature d'un rejeu et non d'un
changement.

Les cles attendues dans `crm_config` pour chaque fournisseur sont documentees dans
`docs/erp-integration-setup.md`.

`CrmSyncService` porte tout ce qui est propre a LeadFlow : resolution de `CrmTarget` depuis
`client.crm_config` dechiffre, reconstruction de l'etat anterieur (valeur non nulle la plus
recente, champ par champ), resolution et memorisation de `sales_rep.crm_ref`, ecriture de la
trace. Il ne choisit aucun commercial (F4), ne consomme aucune file (F3) et ne reessaie pas :
la reprise appartient a la DLQ. La trace s'ecrit en `REQUIRES_NEW` pour survivre au rollback
d'un appelant transactionnel.

Il n'y a **pas de connecteur par defaut** : tout lead appartient a un client, et tout
client nomme son fournisseur. `CrmConnectorRegistry.defaultConnector()` et la propriete
`leadflow.crm.default-provider` ont ete supprimees.

`leadflow.crm.providers.<provider>` ne porte donc que des reglages **techniques** communs a
toutes les instances d'un meme fournisseur (`enabled`, `connect-timeout`, `read-timeout`).
Tout ce qui depend du client — URL, cle d'API, base, utilisateur — vit dans `crm_config`
sur la ligne `client`, chiffre.

