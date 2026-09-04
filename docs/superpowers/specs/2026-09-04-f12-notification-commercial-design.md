# F12 — Notifier le commercial

Conception validee le 4 septembre 2026. Elle decrit **ce qu'il faut construire et pourquoi**,
pas dans quel ordre : le plan d'implementation vient ensuite.

## Le probleme

Un lead traverse aujourd'hui `QUALIFIED -> ROUTED -> SYNCED` sans que personne ne soit
prevenu. Le commercial decouvre son lead s'il regarde le dashboard, ou s'il ouvre son ERP.
Pour un lead chaud arrive un vendredi soir, c'est un week-end perdu.

Le reglage qui decrit ce qui merite une alerte existe pourtant depuis F3.
`ScoringConfig.seuilChaud` est reglable par boutique depuis l'ecran « Bareme », et son Javadoc
porte une promesse jamais tenue : « lu et porte des maintenant pour figer la forme du
document, mais F3 ne s'en sert pas : c'est F4 qui alertera sur les leads chauds ». Il n'a
aujourd'hui qu'un seul consommateur, le badge « chaud » du dashboard, calcule a la lecture.
**Il colore une pastille, il ne declenche rien.**

F12 est cette promesse, avec huit features de retard.

## Le perimetre

**Dedans** : un package `notification/`, une migration `V9`, un canal SMTP derriere un port, un
seuil de notification reglable par boutique, une sonde de configuration, la chronologie du lead
enrichie, et la documentation.

**Dehors, explicitement** : la tache d'agenda dans l'ERP — c'est F14, et elle rouvre le modele
pivot —, le webhook sortant, la notification de l'operateur en plus du commercial, tout digest
ou regroupement d'alertes, et toute notification declenchee par autre chose que `lead.synced`.

## Le point d'accroche

`RabbitMQConfig` declare deja la cle de routage, publiee par `SyncedLeadPublisher` apres chaque
synchronisation reussie :

```java
/** Sortie de la synchronisation ERP. Aucun consommateur metier : le monitoring seul. */
public static final String SYNCED_ROUTING_KEY = "lead.synced";
```

Une nouvelle file `leadflow.leads.notify` s'y lie. **L'echange etant un `DirectExchange`, il
livre a toutes les files liees a une cle** : le monitoring continue de recevoir ce qu'il
recevait, et aucun code existant ne change — ni `CrmSyncService`, ni le publieur, ni la
topologie des files metier. C'est le raisonnement qui a rendu la file d'observation de F6 sure,
reutilise ici pour un vrai consommateur metier. Le commentaire ci-dessus devient faux et se
corrige.

**Pourquoi apres la synchronisation et non apres le routage.** Prevenir sur `lead.routed`
alerterait le commercial d'un lead qui n'est pas encore chez lui dans l'ERP : il cliquerait, ne
trouverait rien, et cesserait de faire confiance a l'alerte. Le prix assume est qu'un lead dont
la synchronisation echoue ne declenche aucune notification — cet echec a deja son canal, le
journal des messages morts.

**Les deux approches ecartees.** Notifier depuis `CrmSyncService` installerait un envoi SMTP de
plusieurs secondes dans le consommateur ERP, et un serveur de mail tombe ferait echouer puis
mourir une synchronisation qui a reussi : c'est la faute que la separation routage/CRM de F4 a
justement evitee. Une cle de routage dediee `lead.notify` ajouterait un publieur, une cle et une
dette de republication de plus pour un resultat identique.

## L'architecture

Package `com.leadflow.notification`, cinquieme etape du pipeline, calque sur `crm/`.

| Classe | Responsabilite |
| --- | --- |
| `NotificationListener` | consomme `leadflow.leads.notify`, ne traduit que le protocole |
| `NotificationService` | tout le metier, testable sans broker, **non transactionnel** |
| `CanalDeNotification` | le port : `void envoie(NotificationLead)` |
| `CanalDeNotificationRegistry` | resolution par injection de `List<CanalDeNotification>` |
| `smtp/CanalSmtp` | l'adaptateur, seul a connaitre JavaMail et le gabarit du message |
| `model/NotificationLead` | le pivot |
| `NotificationAttempt`, son repository, `NotificationTraceWriter` | la trace, en `REQUIRES_NEW` |
| `SondeNotification` | eprouve la configuration sans rien enregistrer |

### Le flux

```
crm/CrmSyncService  --(succes)-->  SyncedLeadPublisher
                                        |  lead.synced
                        +---------------+---------------+
                        v                               v
        leadflow.monitoring.events        leadflow.leads.notify   (NOUVELLE)
              (inchangee)                          |
                                          NotificationListener
                                                   |
                                          NotificationService
                                     lit lead + sales_rep + scoring_config
                                                   |
                                        score >= seuilNotification ?
                                            non -> IGNOREE, trace ecrite
                                            oui -> CanalDeNotification
                                                   |
                                              smtp/CanalSmtp
```

### Les invariants a ne pas casser

**`NotificationLead` ne contient aucun terme propre a un canal** — ni « subject », ni « from »,
ni « html ». Il porte des faits : le nom du commercial, son adresse, le nom du prospect, son
score, son intention, l'URL de sa fiche. L'adaptateur compose l'objet et le corps. C'est
l'invariant de `crm/model`, et il se casse aussi facilement : des qu'un champ SMTP remonte dans
le pivot, un second canal devient impossible sans reecriture.

**Aucun `switch` sur le canal.** Le registre collecte les implementations par injection, comme
`AssignmentStrategyRegistry` et `CrmConnectorRegistry`.

**Le consommateur est un bean conditionnel** (`leadflow.notification.listener.enabled`), retire
dans la suite de tests comme les cinq autres. Sans cela, un consommateur actif volerait aux
tests le message qu'ils viennent de publier. Ne pas le remplacer par `auto-startup=false` : le
cache de contextes de test reveille les beans `Lifecycle` en ignorant ce reglage.

**Le service n'est pas transactionnel**, pour la raison qui vaut deja pour
`LeadQualificationService` : un envoi SMTP peut durer plusieurs secondes et tiendrait une
connexion Postgres pendant tout ce temps. L'ecriture porte sa propre transaction.

**Les contrats de file se declarent dans `RabbitMQConfig.PAQUETS_DE_CONFIANCE`**, la
correspondance etant exacte — ni prefixe, ni joker. Le message consomme est le
`SyncedLeadMessage` existant, dont le paquet est deja de confiance ; tout nouveau type qui
franchirait la file devra y etre ajoute.

## Les donnees

### `V9__notification_attempt.sql`

Calquee sur `crm_sync_attempt`, avec les memes partis.

```sql
CREATE TABLE notification_attempt (
    id            UUID         PRIMARY KEY,
    lead_id       UUID         NOT NULL REFERENCES lead (id) ON DELETE CASCADE,
    -- Porte par la ligne et non deduit de la configuration : si l'instance change de
    -- canal, l'historique reste lisible. Meme parti que crm_sync_attempt.provider_id.
    channel       VARCHAR(40)  NOT NULL,
    -- L'adresse telle qu'elle a servi, pas celle que sales_rep porte aujourd'hui : un
    -- commercial qui change d'e-mail ne doit pas reecrire l'histoire.
    recipient     VARCHAR(255) NOT NULL,
    -- Sans cle etrangere, deliberement : une suppression ne doit pas effacer la trace.
    -- Meme parti que lead_action.previous_sales_rep_id.
    sales_rep_id  UUID,
    status        VARCHAR(32)  NOT NULL
        CONSTRAINT ck_notification_attempt_status
        CHECK (status IN ('ENVOYEE', 'ECHEC', 'IGNOREE')),
    -- Le score au moment de la decision, et le seuil qui l'a tranchee.
    score         INTEGER      NOT NULL,
    seuil         INTEGER      NOT NULL,
    error_message TEXT,
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_attempt_lead ON notification_attempt (lead_id, attempted_at DESC);
```

**`IGNOREE` est un statut, pas une absence de ligne.** Un lead sous le seuil ecrit quand meme sa
trace : c'est ce qui permet de repondre a « pourquoi n'ai-je pas ete prevenu ? » par « score 55,
seuil 70 ». Ne rien ecrire rendrait le silence indistinguable d'une panne.

**`score` et `seuil` sont figes dans la ligne**, pas relus au moment de la question. Le seuil est
modifiable depuis l'ecran « Bareme » : sans ces deux colonnes, le deplacer ferait mentir tout
l'historique. C'est la lecon de `lead.score`, fige a la qualification pour la meme raison.

**`ON DELETE CASCADE`**, comme `crm_sync_attempt` et contrairement a `lead_action` : une
tentative de notification n'a pas de sens sans son lead, la ou un geste humain garde le sien.

### Le seuil de notification, sans migration

Un champ de plus dans `ScoringConfig`, donc dans le document JSON `client.scoring_config` :

```java
int seuilNotification   // defaut : 70, aligne sur seuilChaud
```

`client.scoring_config` est un document precisement pour cela — ajouter un reglage inedit ne
doit demander ni migration ni modification d'entite. La lecture de `ScoringConfig` etant
**tolerante**, un document existant sans la cle prend le defaut : toutes les boutiques deja
configurees continuent de fonctionner sans etre touchees.

**Un seuil distinct de `seuilChaud`, et non le meme.** Les deux repondent a des questions
differentes — « colorer une pastille » et « deranger quelqu'un » — et meritent des valeurs
differentes : on tolere un badge genereux, pas une boite mail saturee. Les confondre donnerait a
`seuilChaud` deux roles, et deplacer le seuil pour ajuster l'affichage changerait silencieusement
qui recoit des e-mails.

Cote API, `ScoringForm` gagne un champ borne `[0, 100]`. Le `PUT` remplacant le document entier,
l'ecran doit l'envoyer, sans quoi une sauvegarde de bareme remettrait le seuil au defaut.

### La chronologie

`LeadTimelineService` gagne une sixieme source. `TimelineEventType.NOTIFICATION` s'insere apres
`SYNC_ERP` dans l'ordre de declaration — qui **est** l'ordre du pipeline, `positionDe(...)`
s'appuyant sur `ordinal()`. Une entree `IGNOREE` y figure aussi : elle explique un silence.

Le service continue de rendre des faits typés, jamais des phrases : le libelle francais rejoint
la table de `lead-timeline.ts`.

## Le comportement en echec

```
1. lit lead, sales_rep, scoring_config de la boutique
2. score < seuil       ->  trace IGNOREE, acquittement, fin
3. envoie via le port
4a. succes             ->  trace ENVOYEE
4b. echec technique    ->  trace ECHEC ecrite EN REQUIRES_NEW, PUIS l'exception part
```

**L'etape 4b est l'invariant.** La trace s'ecrit avant de laisser partir l'exception, dans sa
propre transaction — exactement ce que fait `LeadActionJournal` pour un rejeu rate, et pour la
meme raison : sans cela, la seule trace de l'echec disparaitrait avec lui, et le journal des
morts n'aurait qu'une date a montrer.

Un echec technique — SMTP injoignable, authentification refusee — part ensuite en DLQ apres
trois tentatives, comme la synchronisation ERP. C'est reparable par un humain, et le message
redevient rejouable depuis le dashboard avec un motif.

**Trois cas ne sont pas des echecs et ne partent jamais en DLQ**, aucune repetition ne les
reparant :

| Cas | Traitement |
| --- | --- |
| score sous le seuil | trace `IGNOREE`, acquittement |
| commercial sans adresse e-mail | trace `ECHEC` explicite, acquittement |
| lead sans commercial attribue | trace `ECHEC` explicite, acquittement |

Le dernier est impossible sur `lead.synced` et se traite defensivement. C'est la distinction que
la qualification fait deja avec `DISCARDED` : une erreur deterministe ne part jamais en DLQ,
sans quoi un filet la republierait a chaque tour.

## Les reglages

Cinq proprietes dans un `record @ConfigurationProperties` de `config/`, sous
`leadflow.notification.*` : hote, port, identifiant, mot de passe, adresse d'expedition. Elles
viennent de variables d'environnement, comme les cinq variables existantes.

**Le profil `dev` ne porte aucun repli**, contrairement a la cle maitre et au compte operateur :
l'hote y reste vide, donc le canal est inerte et `spring-boot:run` demarre sans preparer de
relais. Un developpeur qui veut eprouver l'envoi pose la variable vers un collecteur local. Un
repli qui pointerait vers un serveur imaginaire ferait echouer chaque lead chaud en
developpement, et remplirait la DLQ de morts sans interet.

`spring-boot-starter-mail` n'est pas dans le `pom.xml` : c'est une dependance nouvelle.

**Le canal est inerte si l'hote est absent** : l'adaptateur ecrit une trace `ECHEC` explicite et
acquitte, plutot que d'empecher l'instance de demarrer. C'est le parti de `GEMINI_API_KEY`, la
seule variable qui puisse manquer sans consequence — une instance sans SMTP doit continuer a
traiter des leads.

## L'interface

**Le seuil rejoint l'ecran « Bareme »** d'une boutique : un champ de plus dans un formulaire
existant, avec les bornes deja en place pour les poids.

**La sonde rejoint l'ecran « Parametres »**, a cote de la cle Gemini. `SondeNotification` reprend
le parti de `SondeIntent` : elle eprouve la configuration **sans rien enregistrer**, nomme la
cause de l'echec la ou le consommateur avale tout, et les deux partagent `CanalSmtp` — seul
endroit qui connaisse le protocole. Sans elle, un SMTP mal configure ne se decouvre qu'au
premier lead chaud perdu.

La sonde ne recopie jamais la reponse brute du serveur a l'ecran : elle en extrait la phrase
utile — « authentification refusee », « hote injoignable » — et journalise le reste.

Toute decision visuelle passe par les skills du plugin `ui-ux-pro-max`, invoques avant d'ecrire
le code.

## Les tests

**Contractuel, a chaque `./mvnw test`** : `CanalSmtp` contre un serveur SMTP en memoire
(GreenMail), qui **asserte le contenu du message envoye** — destinataire, presence du nom du
prospect et du score — et pas seulement l'absence d'exception. C'est le parti des adaptateurs
ERP, qui assertent les corps envoyes et non les codes retour.

**Integration, marque `@Tag("notification")` et exclu par defaut**, contre un vrai relais, sur le
modele de `@Tag("erp")`. `./mvnw verify` continue de s'executer sans profil supplementaire : la
CI ne sait pas preparer de relais SMTP.

**Le coeur metier se teste sans broker ni serveur** — seuil, cas deterministes, ordre des
ecritures —, `NotificationService` ne dependant que du port.

## Ce que la feature ne resout pas

Un lead chaud dont la synchronisation ERP echoue ne declenche aucune alerte. Une instance sans
SMTP configure n'alerte personne et le dit seulement dans ses traces. Et la notification part au
commercial, jamais a l'operateur de l'agence : personne n'est prevenu qu'une notification a
echoue, sinon en lisant le journal des morts.
