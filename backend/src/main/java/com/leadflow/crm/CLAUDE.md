# Connecteurs ERP/CRM

Ce fichier ne se charge que lorsqu'on travaille sous `backend/src/main/java/com/leadflow/crm/`.
L'invariant qui gouverne ce package — aucun ERP cable en dur, aucun terme propre a un
fournisseur dans le modele pivot — est rappele dans le `CLAUDE.md` de la racine, parce qu'il
engage tout le projet et pas seulement ce repertoire.

```
crm/
├── CrmConnector.java          port : providerId(), sync(...), resolveAssignee, reglagesAttendus, verifieAcces
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

Deux limitations connues vivent dans cette modelisation, documentees dans le Javadoc de
`DolibarrConnector` : le rattachement du responsable Dolibarr n'a pas de logement dans
`CrmSyncState` — s'il echoue apres la creation de l'opportunite, le rejeu saute l'etape sans
le signaler — et la `ref` d'opportunite est tiree au hasard faute de reference de lead dans
le pivot. Les deux appellent la meme decision : elargir le pivot, ou passer d'un triplet de
references a une carte par etape. Elle se prendra avec F3, quand le consommateur de file
dira ce qu'il peut fournir comme identifiant.

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

