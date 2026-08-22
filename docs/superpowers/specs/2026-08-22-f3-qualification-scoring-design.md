# F3 — Qualification et scoring

Statut : validé en session de conception, prêt pour le plan d'implémentation.
Dépend de : F1 (livrée) pour `lead`, `client` et `scoring_config` ; F2 (livrée) pour la
file alimentée et le contrat `CapturedLeadMessage` ; F5 (livrée) pour le modèle pivot.
Position dans le planning général : quatrième feature exécutée, après F1, F5 et F2.

---

## 1. Objectif

Consommer la file et transformer un événement brut en lead qualifié.

À la fin de F3, un message `lead.captured` produit une ligne `lead` portant une identité
normalisée, une intention détectée, un score borné et le statut `QUALIFIED` ; un doublon
est tracé sans être retraité ; une panne de l'API Gemini n'empêche aucun lead d'être
qualifié ; et un message `lead.qualified` part sur le broker à destination de F4.

F3 ferme deux dettes laissées par les features précédentes : la file
`leadflow.leads.captured` cesse d'être un cul-de-sac, et la `ref` d'opportunité tirée au
hasard par `DolibarrConnector` reçoit enfin une référence de lead stable.

Ce que F3 ne fait pas : choisir un commercial (F4), appeler l'ERP (F5 existe mais n'est
toujours appelé par personne), exposer quoi que ce soit en REST (F6).

---

## 2. Contexte et contradictions levées

**Le payload est un JSON libre.** `capture` ne le regarde jamais — c'est son invariant.
F3 est donc le premier étage à devoir l'interpréter, alors que rien ne garantit les noms
de clés qu'un client emploie. Le contrat documenté montre `{source, email, telephone,
message}`, mais `lead` attend en plus société, prénom, nom, pays et secteur.

**`lead.email` est NOT NULL, or un payload peut ne pas en contenir.** F1 a écrit que
`REJECTED` couvre « le doublon et les données invalides », sans voir qu'une donnée
invalide au point de n'avoir aucun email ne peut pas produire de ligne `lead`.

**Les règles sont le mode dégradé.** Le plan général l'énonce : Gemini est le chemin
nominal, l'analyseur à base de règles le repli. La colonne `intent_source` et sa contrainte
`CHECK (intent_source IN ('RULES', 'GEMINI'))` existent depuis V2 pour rendre ce
basculement observable.

**La livraison est at-least-once.** F2 le documente : si la publication réussit mais que
le passage à `PUBLISHED` échoue, `PendingEventRelay` renvoie le message. Le consommateur
de F3 doit donc être idempotent sur `eventId`.

**`client.scoring_config` attend sa forme.** La migration V2 dit explicitement « la forme
du document est définie par F3 ».

---

## 3. Décisions

### 3.1 Mapping par contrat documenté et alias en dur

Les noms de champs canoniques sont documentés, et `PayloadFieldMapper` porte une table
d'alias FR/EN en dur. Aucun réglage par client, aucune migration.

Les clés du payload sont **normalisées avant comparaison** : minuscules, accents retirés,
séparateurs supprimés. `Adresse-Email`, `adresse_email` et `ADRESSE EMAIL` deviennent tous
`adresseemail`. Sans cette normalisation, la table devrait énumérer chaque variante
typographique et en oublierait toujours une.

```
EMAIL     <- email, mail, courriel, adresseemail, emailaddress
PHONE     <- telephone, phone, tel, mobile, gsm, numero
MESSAGE   <- message, commentaire, demande, besoin, comment, body
COMPANY   <- societe, entreprise, company, raisonsociale, organisation
FIRSTNAME <- prenom, firstname, givenname
LASTNAME  <- nom, lastname, nomfamille, surname
COUNTRY   <- pays, country, countrycode
SECTOR    <- secteur, sector, industrie, activite
```

Deux limites assumées, à porter dans le Javadoc : le mapping ne descend pas dans le JSON
— un objet ou un tableau imbriqué est ignoré, seul le premier niveau est lu — et le premier
alias trouvé dans l'ordre déclaré gagne. `source` n'est pas mappé : il est déjà porté par
`raw_lead_event.source`.

L'alternative écartée était un document de mapping par client en base. Plus fidèle à la
philosophie multi-tenant du projet, mais elle coûtait une migration, un document de plus à
maintenir à l'onboarding, et faisait d'un mapping oublié une cause de lead non qualifiable.

### 3.2 Seul l'email peut faire échouer la qualification

| Champ | Traitement |
| --- | --- |
| email | `trim`, minuscules, regex souple (un seul `@`, un point dans le domaine, pas d'espace), ≤ 255 — sinon aucun lead |
| téléphone | chiffres et un `+` initial conservés ; `00` initial converti en `+` ; moins de 6 chiffres → `null` ; tronqué à 32 |
| noms, société | `trim`, espaces multiples réduits, tronqués aux longueurs de colonne (80 / 160) |
| pays | majuscules, retenu seulement si exactement 2 lettres |
| message | `trim`, conservé entier en base (`text`) |

Un téléphone illisible met le champ à `null`, il ne rejette pas le lead : c'est le seul
comportement cohérent avec l'invariant de capture, qui ne valide ni email ni téléphone.

La troncature n'est pas cosmétique. Sans elle, un formulaire mal borné envoyant 300
caractères dans `first_name VARCHAR(80)` provoquerait une erreur Postgres, donc trois
tentatives puis un passage en DLQ, pour une donnée parfaitement exploitable.

### 3.3 Pas d'email exploitable : aucun lead, `raw_lead_event` en FAILED

On ne crée pas de ligne `lead`. `raw_lead_event` passe à `FAILED` avec un `failure_reason`
lisible, et le message est acquitté.

Le journal de capture est là pour ça, et la colonne `failure_reason` existe déjà. La table
`lead` garde son invariant : un lead a toujours une identité joignable, ce dont tout le code
aval — routage, `CrmLead` — dépend implicitement.

L'alternative écartée était une migration rendant `lead.email` nullable pour que tout
événement produise une ligne traçable. Un seul endroit à lire pour F6, mais au prix d'un
invariant utile et d'une redondance avec `raw_lead_event.failure_reason`.

### 3.4 Clé d'API Gemini globale à l'instance

Une seule clé pour toute l'instance, sous `leadflow.intent.gemini.api-key`, alimentée par
`GEMINI_API_KEY` comme `LEADFLOW_MASTER_KEY` l'est pour le chiffrement. L'analyse
d'intention est un service que l'agence rend à ses clients — cohérent avec le modèle SaaS
du projet, et la plupart des clients d'une agence marketing n'ont pas de compte Google
Cloud.

Clé absente ou vide : l'application démarre normalement et qualifie en mode `RULES`. La
qualification ne dépend jamais de la disponibilité d'un service externe.

L'alternative écartée était une clé par client, chiffrée comme `hmac_secret` : elle coûtait
une migration et une étape d'onboarding supplémentaire pour un besoin non exprimé.

### 3.5 Le repli est un décorateur câblé par la configuration, pas un `if`

```java
@Component                                    // toujours présent
class RuleBasedIntentAnalyzer implements IntentAnalyzer { ... }

@Component @Primary
@ConditionalOnProperty("leadflow.intent.gemini.enabled")
class GeminiIntentAnalyzer implements IntentAnalyzer {
    private final IntentAnalyzer repli;       // le rule-based, injecté
}
```

Gemini désactivé, et le seul bean restant est le rule-based : `LeadQualificationService`
ne s'en aperçoit pas. Clé vide alors que `enabled: true` — un avertissement au démarrage,
puis délégation systématique sans appel réseau, plutôt qu'une découverte lead par lead.

`GeminiIntentAnalyzer` ne propage **aucune** exception. Timeout, 5xx, quota dépassé,
réponse hors vocabulaire, JSON illisible : tout retombe sur le repli avec
`IntentSource.RULES`. C'est ce qui rend vrai le critère de recette du plan général, et c'est
la raison d'être de la colonne `intent_source` — le mode dégradé devient une donnée
observable plutôt qu'une affirmation.

Message vide ou absent : aucun appel réseau, `AUTRE` / `RULES`. Message tronqué à 2 000
caractères avant envoi.

Le rule-based marche sur un lexique pondéré par intention, mots normalisés comme les clés
du mapper. L'intention au score le plus élevé gagne ; égalité ou aucune correspondance →
`AUTRE`. Il ne peut structurellement pas échouer, ce qui est la condition pour qu'il serve
de repli.

`LeadIntent` : `DEVIS`, `ACHAT`, `INFORMATION`, `SUPPORT`, `AUTRE`.

### 3.6 `scoring_config` : barème additif à critères fixes

Le jeu de critères est fermé et connu du code. Seuls les poids et les listes cibles sont
configurables par client. Score borné à `[0, 100]`.

```json
{
  "poids": {
    "telephonePresent": 15,
    "societePresente": 10,
    "nomPresent": 5,
    "messagePresent": 10,
    "intention": { "DEVIS": 40, "ACHAT": 40, "INFORMATION": 15, "SUPPORT": 5, "AUTRE": 0 }
  },
  "secteursCibles": ["industrie", "btp"],
  "paysCibles": ["MA", "FR"],
  "bonusCible": 10,
  "seuilChaud": 70
}
```

La lecture est **tolérante** : document vide, partiel, contenant des clés inconnues ou
malformé → les valeurs manquantes prennent le défaut, rien n'échoue. Un client mal
configuré doit produire un score discutable, jamais un lead perdu.

`seuilChaud` est lu et porté par le record mais F3 ne s'en sert pas : c'est F4 qui alertera
sur les leads chauds. Il fait partie du contrat du document dès maintenant pour éviter de
le faire évoluer plus tard.

L'alternative écartée était un moteur de règles déclaratif générique. Plus fidèle à la
philosophie du projet, mais il demandait de spécifier, parser, valider et tester un
mini-langage, et de gérer des documents invalides au runtime.

### 3.7 Sortie sur `leadflow.leads.qualified`

F3 publie une référence, en réutilisant le motif prouvé par F2 : publication
`AFTER_COMMIT`, message porteur d'une référence et non du contenu, DLX commun.

```java
public record QualifiedLeadMessage(UUID leadId, UUID clientId, int score, Instant qualifiedAt) {}
```

```
exchange leadflow.leads
    "lead.qualified"  ->  leadflow.leads.qualified  ->  DLX commun
```

`com.leadflow.qualification` doit être ajouté à `RabbitMQConfig.PAQUETS_DE_CONFIANCE`, la
correspondance étant exacte : sans cela F4 ne pourra pas désérialiser le message.

F4 n'aura plus qu'à être un consommateur, et la sortie de F3 est observable dans la console
RabbitMQ dès cette session.

### 3.8 Référence de lead dérivée de l'UUID

`CrmLead` reçoit un champ `reference`, calculé de façon déterministe depuis l'UUID du lead :

```
lead.id   = 3f2a9c1b-7d4e-4a12-9f03-...
reference = "LF-3F2A9C1B7D4E"
```

Déterministe, donc **stable au rejeu** — ce qui était précisément le défaut du tirage
aléatoire de `DolibarrConnector`. Aucune migration, aucune séquence à gérer.

Douze caractères hexadécimaux et non huit : huit font 32 bits, et par le paradoxe des
anniversaires une collision devient probable vers 65 000 leads, ce qui est atteignable pour
un middleware dont c'est le métier. Douze font 48 bits, collision probable vers ~16
millions. La chaîne reste sous les 32 caractères acceptés par Dolibarr.

Le terme `reference` est neutre : aucun vocabulaire ERP n'entre dans le pivot, l'invariant
de `crm/model` tient.

### 3.9 Un client désactivé entre la capture et la qualification est qualifié normalement

Le webhook refuse les clients inactifs, mais un lead capturé avant la désactivation peut
être qualifié après. Il est qualifié normalement : il a été légitimement reçu, et le jeter
silencieusement ferait disparaître une donnée que le client a payée. La désactivation ferme
l'entrée, elle ne vide pas la file.

---

## 4. Organisation du code

`LeadQualificationListener` est une classe distincte de l'orchestrateur : le listener
traduit le protocole (message, ack, rejet), le service porte le métier et ne connaît pas
RabbitMQ. C'est ce qui permet de tester toute la qualification sans broker.

**L'appel Gemini se fait hors transaction.** Un appel HTTP de plusieurs secondes à
l'intérieur d'une transaction JPA tiendrait une connexion Postgres ouverte pendant toute sa
durée : sous charge, le pool s'épuise avant le broker.

```
leadflow.leads.captured
   |  CapturedLeadMessage(eventId, clientId, source, receivedAt)
   v
LeadQualificationListener            traduit le protocole, rien d'autre
   v
LeadQualificationService             orchestration, hors transaction
   |
   +-1- RawLeadEventRepository.findById(eventId)     absent -> ack, log warn
   +-2- LeadRepository.findByRawEventId(eventId)     present -> ack (rejeu)
   |
   +-3- PayloadFieldMapper       Map<String,Object> -> ChampsBruts
   +-4- ContactNormalizer        ChampsBruts        -> ContactNormalise
   |        pas d'email exploitable -> raw_event FAILED + ack, aucun lead
   |
   +-5- DuplicateGuard           (clientId, email, fenetre)
   |        doublon -> lead REJECTED, score 0, aucune publication, ack
   |
   +-6- IntentAnalyzer.analyse(message)        seul appel reseau, HORS transaction
   |        Gemini d'abord, repli RULES sur panne / timeout / desactivation
   |
   +-7- LeadScorer.score(contact, intention, scoringConfig)   -> 0..100
   |
   +-8- LeadWriter.insere(lead)  [TX REQUIRES_NEW]  status = QUALIFIED
             violation de contrainte sur raw_event_id -> relecture + ack
   v
QualifiedLeadPublisher   @TransactionalEventListener(AFTER_COMMIT)
   v
leadflow.leads.qualified
```

**L'ordre des étapes n'est pas arbitraire.** La déduplication est en 5, avant l'analyse
d'intention : un doublon ne doit pas coûter un appel Gemini. La normalisation est en 4,
avant la déduplication : `Karim@ACME.test` et `karim@acme.test ` sont le même prospect mais
deux chaînes différentes. Et l'idempotence est vérifiée deux fois — en 2 par une lecture
optimiste, en 8 par la contrainte unique `raw_event_id`, seule à faire foi quand deux
livraisons arrivent en parallèle. C'est le motif exact de `RawLeadEventWriter` en F2.

```
qualification/
├── LeadQualificationListener.java    @RabbitListener, traduction protocole
├── LeadQualificationService.java     orchestration des 8 étapes
├── PayloadFieldMapper.java           alias en dur -> ChampsBruts
├── ChampsBruts.java                  record, sortie du mapping
├── ContactNormalizer.java            email + téléphone, validation souple
├── ContactNormalise.java             record, sortie de la normalisation
├── DuplicateGuard.java               fenêtre client + email
├── IntentAnalyzer.java               PORT : analyse(String) -> IntentAnalysis
├── IntentAnalysis.java               record (LeadIntent, IntentSource)
├── LeadIntent.java                   enum DEVIS, ACHAT, INFORMATION, SUPPORT, AUTRE
├── RuleBasedIntentAnalyzer.java      lexique, mode dégradé, jamais en échec
├── GeminiIntentAnalyzer.java         RestClient, décorateur à repli
├── LeadScorer.java                   barème additif borné
├── ScoringConfig.java                record + lecture tolérante du JSONB
├── LeadReference.java                dérivation déterministe depuis l'UUID
├── LeadWriter.java                   insertion en REQUIRES_NEW
├── QualifiedLeadMessage.java         contrat de file consommé par F4
├── QualifiedLeadPublisher.java       publication AFTER_COMMIT
└── (existants : Lead, LeadRepository, LeadStatus, IntentSource)
```

`LeadWriter` est un bean distinct et non une méthode privée : Spring ne proxie pas
l'auto-invocation, la propagation `REQUIRES_NEW` serait silencieusement ignorée.

### Fichiers touchés hors du package

Trois, tous pour la même raison — publier vers F4 et fermer la dette de F5 :

- `config/RabbitMQConfig.java` — queue `leadflow.leads.qualified`, sa routing key, son
  binding vers le DLX commun, et `com.leadflow.qualification` ajouté à
  `PAQUETS_DE_CONFIANCE`.
- `crm/model/CrmLead.java` — ajout de `reference`.
- `crm/dolibarr/DolibarrConnector.java` — `ref` d'opportunité alimentée par
  `lead.reference()` au lieu du tirage aléatoire ; le Javadoc de la limitation connue est
  retiré puisqu'elle est levée. `crm/CrmSyncService.java` renseigne le nouveau champ.

---

## 5. Configuration

```yaml
leadflow:
  qualification:
    # Fenêtre de déduplication : même client, même email normalisé.
    dedup-window: 24h
  intent:
    gemini:
      enabled: true
      # Absente ou vide : l'application démarre et qualifie en mode RULES.
      api-key: ${GEMINI_API_KEY:}
      # Propriete et non constante : un test d'integration peut la pointer vers un port mort
      # et verifier que le mode degrade est cable de bout en bout, sans appel reseau sortant.
      base-url: https://generativelanguage.googleapis.com/v1beta/models/
      model: gemini-2.5-flash
      connect-timeout: 3s
      read-timeout: 8s
      # Garde-fou sur le texte envoyé au modèle.
      max-message-chars: 2000
```

Deux `record` `@ConfigurationProperties` dans `config/`, conformément à la convention du
projet. L'identifiant exact du modèle Gemini est à confirmer contre la documentation Google
au moment de l'implémentation ; il est une propriété précisément pour ne pas être figé dans
le code.

---

## 6. Migration

**Aucune.** `CLAUDE.md` annonçait le schéma complet après F1, et les décisions ci-dessus le
confirment : la référence est dérivée et non stockée (3.8), les rejets sans email vivent
dans `raw_lead_event` (3.3), et `scoring_config` existe depuis V2 (3.6).

---

## 7. Gestion d'erreurs

Le principe : **une erreur déterministe ne va jamais en DLQ.** Un payload sans email
échouera identiquement aux trois tentatives et au rejeu manuel ; l'y envoyer ne ferait que
polluer l'écran destiné aux vraies pannes. La DLQ est réservée à ce qui a une chance de
réussir plus tard.

| Situation | Traitement | Trace |
| --- | --- | --- |
| `raw_lead_event` introuvable | ack + log `warn` | — |
| Lead déjà existant pour cet `eventId` | ack silencieux | le lead existant |
| Aucun email exploitable | ack | `raw_event` FAILED + `failure_reason` |
| Doublon dans la fenêtre | ack | `lead` REJECTED, score 0 |
| Téléphone / pays / nom illisibles | on continue | champ à `null` |
| Message absent ou vide | on continue | `AUTRE` / `RULES` |
| Gemini indisponible, lent, hors vocabulaire | on continue | `RULES` dans `intent_source` |
| Postgres ou RabbitMQ injoignable | exception, 3 tentatives, DLQ | DLQ |
| Livraison concurrente du même `eventId` | contrainte unique, relecture, ack | le lead gagnant |

### Sécurité : le message libre entre dans un prompt

Un prospect peut écrire « ignore les instructions précédentes » dans le champ message. La
parade n'est pas de filtrer le texte — c'est perdu d'avance — mais de **contraindre la
sortie** : la réponse n'est acceptée que si elle appartient au vocabulaire fermé de
`LeadIntent`, sinon repli sur les règles. Le pire qu'une injection obtienne est une
intention mal étiquetée sur son propre lead, ce qu'un texte trompeur obtiendrait de toute
façon. Aucune donnée d'un autre tenant n'entre dans le prompt.

---

## 8. Tests

### Étage 1 — unitaire, sans Spring ni base

| Classe testée | Cas qui comptent |
| --- | --- |
| `PayloadFieldMapper` | alias FR et EN ; `Adresse-Email` / `adresse_email` / `ADRESSE EMAIL` tombent sur le même champ ; objet imbriqué ignoré ; `email` gagne sur `mail` quand les deux sont présents ; payload vide |
| `ContactNormalizer` | `Karim@ACME.test ` → `karim@acme.test` ; `06 12-34.56 78` → `0612345678` ; `00212612345678` → `+212612345678` ; téléphone à 3 chiffres → `null` sans rejeter le lead ; prénom de 300 caractères tronqué à 80 ; email sans `@` → rejet |
| `RuleBasedIntentAnalyzer` | « je veux un devis » → DEVIS ; « combien ça coûte » → DEVIS ; « je veux commander » → ACHAT ; texte hors lexique → AUTRE ; message vide et `null` → AUTRE, jamais d'exception |
| `LeadScorer` | barème par défaut sur config vide ; poids surchargés ; document partiel ; clé inconnue ignorée ; JSON malformé → défauts ; score borné à 100 et à 0 |
| `LeadReference` | déterminisme sur le même UUID ; format `LF-` + 12 hex majuscules ; longueur ≤ 32 |

### Étage 2 — contractuel, `MockRestServiceServer`

Même exigence que pour les adaptateurs ERP en F5 : **on asserte le corps envoyé, pas
seulement le code retour.** Un test qui vérifie « ça n'a pas planté » ne détecte pas un
prompt cassé.

- La requête part sur le bon modèle et porte le message du prospect dans son corps.
- Réponse valide `"DEVIS"` → `IntentAnalysis(DEVIS, GEMINI)`.
- Timeout → `RULES`, et le lexique a bien été consulté.
- HTTP 429 et 500 → `RULES`, aucune exception ne sort.
- Réponse hors vocabulaire (`"PEUT-ETRE"`) → `RULES`. C'est le test qui verrouille la
  parade à l'injection de prompt.
- JSON illisible → `RULES`.
- Clé d'API vide → aucun appel réseau (le `MockRestServiceServer` reste vierge), repli
  immédiat.

### Étage 3 — intégration, `@SpringBootTest` + Testcontainers

`@SpringBootTest` et non `@DataJpaTest` : la tranche JPA n'inclut pas les `@Component`,
donc les converters de chiffrement ne seraient pas injectés et la lecture de `client`
échouerait.

Bout en bout : publier un `CapturedLeadMessage` sur `leadflow.leads.captured`, vérifier la
ligne `lead` en base **et** le message présent sur `leadflow.leads.qualified`. Gemini est
désactivé par propriété dans ces tests — aucun appel réseau sortant en CI.

- Rejeu du même `eventId` → un seul lead, second message acquitté.
- Livraison concurrente du même `eventId` → deux threads, un seul lead, aucune exception.
  C'est le test qui prouve que la contrainte unique fait le travail, pas la lecture
  optimiste.
- Aucun email → zéro lead, `raw_event` FAILED avec un motif lisible, et le message **n'est
  pas** en DLQ.
- Postgres coupé → le message finit bien en DLQ après trois tentatives.

---

## 9. Critères de recette

Les trois critères du plan général ont chacun un test nommé :

| Critère | Test |
| --- | --- |
| Un doublon n'est pas traité deux fois | deux événements distincts, même client, même email, dans la fenêtre → le second est `REJECTED`, aucune publication sur `lead.qualified` |
| Un message sans texte libre ne fait pas échouer la qualification | payload `{email}` seul → lead `QUALIFIED`, intention `AUTRE`, score du barème sans message |
| Une panne Gemini bascule sur les règles sans perdre le lead | Gemini en timeout → lead `QUALIFIED` avec `intent_source = RULES`, publié normalement |

Le troisième compte double : c'est la seule vérification que le mode dégradé est câblé de
bout en bout, et pas seulement à l'intérieur de l'analyseur.

S'ajoutent :

- Un `DolibarrConnector` rejoué deux fois sur le même lead envoie deux fois la même `ref`.
- L'application démarre sans `GEMINI_API_KEY` et qualifie en mode `RULES`.

---

## 10. Hors périmètre

- **Choix du commercial et notification** : F4. `lead.assigned_sales_rep_id` reste `null`.
- **Appel à `CrmSyncService`** : rien ne l'appelle encore en dehors des tests. F4 le
  branchera, une fois `assigneeRef` résolu.
- **API REST et dashboard** : F6.
- **Filet de republication de `lead.qualified`** : voir 11.1.
- **Mapping de payload par client** : écarté en 3.1, à rouvrir seulement si un client réel
  le demande.

---

## 11. Risques et dettes assumées

### 11.1 Pas de filet de republication pour `lead.qualified`

F2 a `PendingEventRelay`, qui reprend les `raw_lead_event` restés non publiés. F3 n'aura
pas d'équivalent, et c'est délibéré.

Si la publication de `lead.qualified` échoue après le commit, le lead reste en base avec
`status = QUALIFIED` : rien n'est perdu, mais rien ne le republie. Le filet symétrique
consisterait à rebalayer les leads `QUALIFIED` plus vieux que N minutes. Le problème : tant
que F4 n'existe pas, **aucun lead ne quitte jamais `QUALIFIED`**, et ce filet republierait
en boucle l'intégralité de la table.

C'est F4, qui fait passer le lead à `ROUTED`, qui rend ce balayage possible et borné. La
dette est nommée dans le Javadoc de `QualifiedLeadPublisher`, avec la requête qu'il faudra :
`findByStatusAndCreatedAtBefore(QUALIFIED, seuil)`.

### 11.2 Qualité du lexique de repli

Le rule-based ne sera jamais aussi bon que Gemini. C'est acceptable tant qu'il est le mode
dégradé, mais si la clé d'API n'est jamais fournie, il devient le mode nominal sans que
personne ne s'en aperçoive. `intent_source` rend le fait mesurable ; F6 devra l'afficher.

### 11.3 Le seuil de déduplication est global

`dedup-window` est une propriété d'instance, pas de client. Un client au cycle de vente
long et un client en e-commerce n'ont pas la même notion de doublon. À déplacer dans
`scoring_config` si le besoin se manifeste — le document est déjà par client.

### 11.4 La table d'alias est en dur

Un client au formulaire exotique demandera une modification de code et un redéploiement, ce
qui contredit le principe « ajouter un client est une insertion en base ». Assumé en 3.1 ;
la porte de sortie est une surcharge par client dans `scoring_config`, sans migration.

---

## 12. Prochaine étape

Plan d'implémentation via la skill `writing-plans`, puis exécution sur la branche
`feature/f3-qualification-scoring`.
