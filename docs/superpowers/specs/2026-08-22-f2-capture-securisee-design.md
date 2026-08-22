# F2 — Capture sécurisée

Statut : validé en session de conception, prêt pour le plan d'implémentation.
Dépend de : F1 (livrée) pour `client`, `raw_lead_event` et le chiffrement des secrets.
Position dans le planning général : troisième feature exécutée, après F1 et F5.

---

## 1. Objectif

Ouvrir l'entrée du pipeline. Un formulaire client signe sa soumission, l'envoie au webhook,
et LeadFlow répond `202 Accepted` après avoir écrit l'événement brut et l'avoir mis en
route vers la file.

À la fin de F2, une soumission signée valide produit une ligne `raw_lead_event` **et** un
message `lead.captured` sur RabbitMQ ; une soumission mal signée est rejetée sans laisser
de trace exploitable par un attaquant ; et aucune soumission acceptée ne peut disparaître
entre la base et le broker.

Personne ne consomme encore ce message : F3 s'en chargera. F2 livre l'émetteur et son
contrat.

---

## 2. Contexte et contradictions levées

**Le webhook ne fait aucun travail métier.** C'est l'invariant que `CLAUDE.md` place au
centre du projet : la couche `capture` authentifie, écrit, publie. Tout ce qui peut être
lent ou échouer — NLP, ERP — vit derrière la file. Une validation métier du contenu du
formulaire, même légère, appartient à F3.

**La table est déjà taillée pour le sujet.** `V1__raw_lead_event.sql` porte `status`
(`RECEIVED` / `PUBLISHED` / `FAILED`), `published_at`, `failure_reason` et un index sur
`status`. F1 a anticipé un mécanisme de reprise de publication ; F2 l'implémente au lieu
d'inventer une autre structure.

**Deux trous dans la configuration existante.** `WebhookProperties` ne connaît que
`signature-header` et `tolerance` : rien ne dit comment l'horodatage voyage. Et
`@EnableScheduling` n'est activé nulle part, alors que le filet de publication en a besoin.

**`common/` ne contient aucune gestion d'erreurs REST**, que `CLAUDE.md` annonce pourtant.
F2 étant le premier endpoint du projet, c'est elle qui la pose.

---

## 3. Décisions

### 3.1 L'appelant est un snippet que nous fournissons

LeadFlow livre au client le code à poser sur son formulaire. Le contrat d'appel est donc
imposé, pas subi : format du corps, en-tête de signature et calcul du HMAC sont décidés
ici et documentés dans `docs/webhook-integration.md`.

### 3.2 Signature et horodatage dans un seul en-tête

```
POST /api/webhooks/leads/{clientKey}
X-Leadflow-Signature: t=1755820000,v1=9f86d081884c7d65...
Content-Type: application/json

{"source":"formulaire-devis","email":"karim@acme.test", ...}
```

La signature porte sur `t + "." + corps brut`, avec `HMAC-SHA256(client.hmac_secret, ...)`.

Un seul en-tête plutôt que deux : l'horodatage fait partie de la charge signée, donc il ne
peut pas être modifié sans invalider la signature. C'est la convention de Stripe, que les
intégrateurs connaissent.

L'horodatage n'est **pas** placé dans le corps JSON : il faudrait désérialiser une entrée
non authentifiée pour décider si on l'authentifie.

### 3.3 Le corps n'est jamais désérialisé avant authentification

Le contrôleur reçoit le corps en `String` brut. Il faut de toute façon les octets exacts
pour recalculer le HMAC — les récupérer après un aller-retour Jackson ne garantirait plus
l'égalité. Le parsing n'a lieu qu'une fois l'appelant authentifié.

Conséquence testable : un corps JSON invalide accompagné d'une signature invalide doit
répondre `401`, jamais `400`.

### 3.4 Idempotence par index unique `(client_id, signature)`

La fenêtre de tolérance borne le rejeu, elle ne l'empêche pas : dans les cinq minutes, une
requête captée peut être renvoyée telle quelle. Un index unique sur
`(client_id, signature)` ferme la fenêtre — un rejeu exact retombe sur la ligne existante,
dont on rend l'identifiant avec un `202`, sans créer de second événement ni republier.

Le prix est une migration `V3`, alors que `CLAUDE.md` annonçait le schéma comme complet.
L'écart est assumé : il achète en plus l'idempotence pour un client qui réessaie après un
timeout réseau, cas nettement plus fréquent que l'attaque.

La colonne `signature` stocke la **valeur d'en-tête complète** (`t=…,v1=…`) et non le seul
hexadécimal : c'est ce couple qui identifie un rejeu exact.

### 3.5 Publication après commit, avec un filet

La publication ne doit pas partir avant que la transaction d'écriture soit validée, sous
peine de mettre en file un message désignant une ligne inexistante.

```
LeadCaptureService  @Transactional
   INSERT raw_lead_event (RECEIVED) ; publie LeadCapturedEvent (evenement Spring)
        |
     COMMIT ------------------------------> 202 Accepted { eventId }
        |
LeadEventPublisher  @TransactionalEventListener(AFTER_COMMIT)
   RabbitTemplate -> leadflow.leads / lead.captured
   puis status = PUBLISHED, published_at = now()   en REQUIRES_NEW
        |
        v  (broker indisponible, ou processus tue juste apres le commit)
PendingEventRelay  @Scheduled
   republie les RECEIVED et FAILED plus vieux que relay-after
```

`AFTER_COMMIT` seul serait plus simple mais laisserait un lead perdu en silence dès que le
broker est indisponible. Un outbox pur — seul le relais publie — supprimerait ce chemin
double, au prix d'une latence d'entrée égale à la période de balayage. Le couple retenu
donne la latence de l'un et la garantie de l'autre.

Le changement de statut s'écrit en `REQUIRES_NEW` parce que la transaction de capture est
déjà commitée quand le listener s'exécute — même raisonnement que `CrmSyncTraceWriter` en
F5.

**Doublon possible, assumé.** Si la publication réussit mais que le passage à `PUBLISHED`
échoue, le relais republiera le message. Le consommateur de F3 devra donc être idempotent
sur `eventId`. C'est le compromis normal d'un at-least-once ; l'inverse — un lead perdu —
serait une violation de la garantie annoncée par `CLAUDE.md`.

`relay-after` est nettement plus long que la durée d'une publication normale, pour que le
filet ne double jamais une publication en cours.

### 3.6 Refus uniformes

Cinq causes d'échec d'authentification — clé publique inconnue, client inactif, en-tête
absent, signature fausse, horodatage hors fenêtre — produisent **la même** réponse `401`
avec le même corps. Distinguer les codes offrirait à qui sonde l'API un oracle sur les
clés publiques existantes et sur l'état des clients.

Le détail exact part dans les logs serveur en `WARN`, avec la clé publique tentée. Ni le
secret, ni la signature attendue n'y figurent jamais.

Corps illisible : `400`. Corps au-delà de `max-payload-bytes` : `413`.

### 3.7 Le seul champ exigé du corps est `source`

`raw_lead_event.source` est `NOT NULL` : la capture a besoin de savoir de quel canal du
client vient la soumission. Le contrat impose donc un champ `source` à la racine du JSON ;
son absence donne `400`, une fois l'appelant authentifié.

Ce n'est pas une validation métier au sens de 9 : le middleware ne regarde ni l'email, ni
le message, ni aucun autre champ. `source` est une métadonnée d'acheminement, exigée par le
schéma, et le snippet que nous fournissons la pose lui-même.

Tout le reste du corps est stocké tel quel dans `payload`, sans être inspecté.

---

### 3.8 Le message porte une référence, pas le contenu

```json
{
  "eventId": "018f...",
  "clientId": "018e...",
  "source": "formulaire-devis",
  "receivedAt": "2026-08-22T00:00:00Z"
}
```

La base reste l'unique source de vérité : F3 relit `raw_lead_event.payload`. Les messages
restent minuscules, le payload n'existe pas en double, et un rejeu depuis la DLQ travaille
forcément sur la donnée à jour.

---

## 4. Organisation du code

```
capture/
├── LeadWebhookController.java   endpoint ; lit le corps brut, ne decide rien
├── LeadCaptureService.java      @Transactional : client, signature, parsing, insertion
├── HmacSignatureVerifier.java   pur : parse t=..,v1=.. ; fenetre ; HMAC ; temps constant
├── LeadCapturedEvent.java       evenement applicatif interne (record)
├── LeadEventPublisher.java      AFTER_COMMIT -> RabbitMQ -> statut en REQUIRES_NEW
├── PendingEventRelay.java       @Scheduled : filet de republication
├── CapturedLeadMessage.java     contrat de file consomme par F3 (record)
├── RawLeadEvent.java            (F1)
└── RawLeadEventRepository.java  + findByClientIdAndSignature, findByStatusInAndReceivedAtBefore

common/
├── WebhookAuthenticationException.java   une exception pour les cinq causes
└── ApiExceptionHandler.java              @RestControllerAdvice -> ProblemDetail

config/
├── SchedulingConfig.java        @EnableScheduling
└── WebhookProperties.java       + max-payload-bytes, relay-after, relay-interval
```

`HmacSignatureVerifier` ne dépend ni de Spring ni de la base : il reçoit le secret, le
corps, l'en-tête et l'instant courant. C'est ce qui le rend testable sans conteneur et sans
`Thread.sleep`.

`CapturedLeadMessage` est la frontière publique de `capture/` : F3 ne connaîtra rien
d'autre de cette couche.

---

## 5. Configuration

```yaml
leadflow:
  webhook:
    signature-header: X-Leadflow-Signature
    tolerance: 5m
    max-payload-bytes: 65536
    relay-after: 2m        # age minimal d'une ligne avant republication
    relay-interval: 30s    # periode du balayage
```

Aucun secret ici : le secret de signature est `client.hmac_secret`, chiffré au repos.

---

## 6. Migration

`V3__raw_lead_event_idempotence.sql` :

- index unique sur `(client_id, signature)` — l'idempotence de 3.4 ;
- index composite sur `(status, received_at)` — la requête du relais, l'index simple sur
  `status` de V1 ne suffisant pas à borner par âge.

---

## 7. Tests

**Unitaire, sans Spring — `HmacSignatureVerifierTest`.** Signature valide acceptée ;
en-tête absent ; en-tête malformé ; `v1` faux ; horodatage périmé
(`rejectsExpiredTimestamp`) ; horodatage trop loin dans le futur. L'horloge est un
paramètre : aucun test ne dort.

**Intégration, Testcontainers Postgres + RabbitMQ.**

- Requête signée valide → `202`, une ligne en base, et le message **réellement consommé
  depuis la file** — pas un `RabbitTemplate` simulé.
- Les cinq causes d'échec → `401`, corps identique, aucune ligne créée.
- Corps JSON invalide **avec** signature invalide → `401` et non `400` : c'est la preuve
  que rien n'est désérialisé avant authentification (3.3).
- Rejeu de la même requête → une seule ligne, deux fois le même `eventId`, deux `202`.
- Publication en échec → ligne laissée `RECEIVED` / `FAILED`, puis republiée par
  `PendingEventRelay` ; une ligne récente ou déjà `PUBLISHED` n'est pas touchée.

---

## 8. Critères de recette

1. `./mvnw verify` passe, tests d'intégration inclus.
2. Une signature invalide, absente ou périmée est rejetée en `401`, sans distinction de
   cause observable de l'extérieur.
3. Une signature valide produit une ligne `raw_lead_event` **et** un message dans
   `leadflow.leads.captured`.
4. Le même appel rejoué ne crée qu'une ligne et rend le même `eventId`.
5. Broker arrêté : la requête répond `202`, la ligne existe, et le relais publie une fois
   RabbitMQ revenu. Aucun lead perdu.
6. Le corps n'est jamais désérialisé avant authentification.
7. Le `curl` de `docs/webhook-integration.md` fonctionne tel quel contre le backend en dev.
8. `CLAUDE.md` décrit l'endpoint, le contrat de signature et le mécanisme de publication.

---

## 9. Hors périmètre

- **Toute consommation de la file** : F3.
- **Toute validation métier du contenu** — champs obligatoires, format d'email,
  déduplication métier : F3.
- **Limitation de débit par client** : F7.
- **Garde-fou mémoire en amont** : le plafond `413` est appliqué après que Spring a lu le
  corps ; il protège la base et la file, pas la mémoire du serveur. Un vrai garde-fou
  relève du reverse-proxy, en F7.
- **Snippet client livrable** : F2 documente le contrat et le calcul de signature, sans
  livrer de code client à maintenir.

---

## 10. Risques

**Le doublon en file (3.5).** Assumé et documenté ; il impose une contrainte à F3, qui
devra être idempotent sur `eventId`. À rappeler dans `CLAUDE.md`, sans quoi F3 la
découvrira en production.

**L'index unique sur une colonne de 255 caractères.** La valeur d'en-tête complète y tient
largement, mais un futur schéma de signature plus verbeux la ferait déborder. À vérifier si
le format de signature change.

**Le relais et le multi-instance.** Deux instances de l'application balaieraient les mêmes
lignes en même temps. Sans verrou, elles publieraient deux fois. F2 vise le déploiement
mono-instance du projet ; un `SELECT ... FOR UPDATE SKIP LOCKED` serait la réponse le jour
où ça change — à noter dans `CLAUDE.md` plutôt qu'à implémenter maintenant.

---

## 11. Prochaine étape

Plan d'implémentation via la compétence `superpowers:writing-plans`, sur la branche
`feature/f2-capture-securisee`.
