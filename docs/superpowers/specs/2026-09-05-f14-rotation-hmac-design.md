# F14 — Rotation HMAC à fenêtre de transition

_Design validé le 5 septembre 2026. Une session courte. Touche `capture/`, `tenant/`, le
frontend, et une onzième migration._

## Le défaut réparé

`ClientAdminService.tourneLeSecret` fait aujourd'hui `client.setHmacSecret(nouveau)` : le
nouveau secret prend effet à l'instant du commit, et `HmacSignatureVerifier.verifie` n'en
connaît qu'un seul. Or le site de la boutique continue de signer avec l'ancien jusqu'à ce que
son développeur redéploie — quelques heures, parfois quelques jours. Entre les deux, **chaque
lead est refusé en `401`**, indiscernable d'une attaque, et la boutique ne s'en aperçoit qu'en
constatant que plus rien n'arrive.

Autrement dit : le geste de sécurité le plus élémentaire du produit provoque aujourd'hui une
perte de leads silencieuse. F14 fait cohabiter l'ancien et le nouveau secret pendant une
fenêtre bornée, et donne à l'opérateur de quoi savoir quand il peut la fermer.

## Les décisions prises, et leurs raisons

**La durée de la fenêtre est fixe et globale à l'instance** —
`leadflow.webhook.transition-secret`, défaut `24h`, ajoutée à `WebhookProperties`. Comme le
fuseau des séries de F13 et les réglages du relais SMTP de F12, elle n'est pas portée par la
boutique : c'est un paramètre d'exploitation de l'agence, pas une caractéristique commerciale
d'un client. La rendre choisissable à chaque rotation aurait ajouté un champ, une validation de
bornes et un paramètre d'API pour un geste qui arrive une ou deux fois dans la vie d'une
boutique.

**Une seconde rotation pendant la fenêtre est autorisée**, et l'ancien secret devient alors
celui qu'on vient de remplacer. Il n'y a donc **jamais plus de deux secrets vivants**. La
conséquence est réelle et l'écran doit la dire : le secret d'origine cesse immédiatement de
valoir, donc une boutique qui n'avait pas encore migré se met à être refusée. Refuser la
seconde rotation aurait protégé de la double manipulation par inadvertance, au prix d'un geste
supplémentaire exactement au moment où l'on veut aller vite — une fuite avérée.

**L'ancien secret doit expirer.** Une fenêtre sans fin, ce sont deux secrets permanents : le
double de surface d'attaque pour la même porte. C'est la contrainte non négociable de la
feature.

**L'expiration est paresseuse, sans tâche planifiée.** Une fenêtre close n'est pas balayée : le
secret précédent reste en base, chiffré, mais le vérificateur ne le reçoit plus jamais puisque
`LeadCaptureService` compare `previous_secret_expires_at` à l'instant courant. Il disparaît à la
rotation suivante ou à la révocation. Le prix assumé : un secret expiré dort au repos jusque-là.
Le nettoyer depuis le chemin de capture ferait payer une écriture sur `client` à des requêtes
qui n'ont rien à corriger, et un planificateur pour trois lignes serait disproportionné.

**Chaque capture signée avec l'ancien secret est marquée**, par un booléen posé sur la ligne
`raw_lead_event` que l'on est de toute façon en train d'écrire — le chemin chaud ne paie aucune
écriture supplémentaire. C'est ce qui permet à l'écran de répondre à la seule question que
l'opérateur se pose vraiment : « puis-je révoquer maintenant ? ». Un compteur en mémoire aurait
évité la migration, mais il serait mono-instance et perdu à chaque redémarrage — donc faux
précisément quand on le regarde.

## Les invariants que cette feature frôle

- **Les cinq causes de refus rendent le même `401`.** La fenêtre n'en ajoute pas une sixième et
  ne change aucun message : « vous signez avec l'ancien secret » dirait à un attaquant qu'il
  approche. Un secret expiré est indiscernable d'une signature fausse.
- **La clé d'idempotence `(client_id, signature)` de `V3` n'est pas affectée.** La forme
  canonique reste `t=<epoch>,v1=<hex>` reconstruite à partir des valeurs validées, et
  l'hexadécimal est celui qui a correspondu — donc une même soumission rejouée porte toujours la
  même clé, quel que soit le secret qui l'a acceptée.
- **Le corps n'est toujours pas désérialisé avant authentification.** Rien dans cette feature ne
  déplace la vérification, qui reste dans `capture/` et hors de la chaîne Spring Security.
- **Hibernate ne crée jamais de table** : l'évolution passe par `V11`.
- **Aucune entité JPA ne franchit la frontière HTTP** : les deux nouveaux champs sortent par des
  `record` de `tenant/dto/`.

## Le modèle de données — migration `V11`

Trois colonnes, aucune table nouvelle.

Sur `client` :

- `previous_hmac_secret TEXT` — chiffrée au repos par le même `EncryptedStringConverter` que
  `hmac_secret` ; rien de nouveau à écrire.
- `previous_secret_expires_at TIMESTAMPTZ`.

Sur `raw_lead_event` :

- `signed_with_previous_secret BOOLEAN NOT NULL DEFAULT false`.

Les deux colonnes de `client` sont **nullables et sans remplissage rétroactif**, comme `V7` :
aucune boutique existante n'est en transition, et l'absence de valeur est exactement le fait à
représenter. Les deux vont toujours ensemble — un secret précédent sans date d'expiration serait
un secret permanent, c'est-à-dire l'inverse de la feature. Elles sont posées et effacées dans la
même transaction, par les mêmes méthodes de `ClientAdminService`.

Aucun index n'est ajouté. `dernierUsage` filtre sur `client_id` et trie par `received_at`, ce que
`idx_raw_lead_event_client_received` de `V2` sert déjà.

## Le vérificateur

`HmacSignatureVerifier.verifie` passe de `(String secret, ...)` à `(List<String> secrets, ...)`
et rend un record au lieu d'une `String` :

```java
public record SignatureVerifiee(String canonique, boolean secretPrecedent) {}
```

**L'ordre de la liste est porteur de sens** : le secret courant d'abord, donc le cas normal ne
calcule qu'un seul HMAC. Le second n'est essayé que si le premier ne correspond pas —
c'est-à-dire pendant une transition, et sur les tentatives réellement fausses.

**Le contrôle de l'horodatage et l'analyse de l'en-tête restent faits une seule fois**, avant
toute comparaison de secret. Un en-tête malformé est refusé immédiatement, sans qu'on essaie
deux secrets contre un texte qui n'en contient pas.

Ce qui ne change pas, et qui est la raison de ce découpage : **le vérificateur ne connaît ni la
base, ni HTTP, ni l'horloge**. Il ignore jusqu'au mot « transition » — il reçoit une liste de
secrets acceptables et n'a pas à savoir pourquoi elle en contient deux. C'est ce qui rend ses
tests instantanés, sans conteneur et sans faire dormir un test, et la feature ne doit pas le
perdre.

L'approche écartée était d'appeler deux fois le vérificateur depuis la capture, en rattrapant
l'exception du premier appel. Elle n'aurait rien changé au vérificateur ni à ses tests, mais
elle refait l'analyse de l'en-tête et le contrôle de l'horodatage — qui ne dépendent pas du
secret — et surtout elle pilote le flux normal par une exception rattrapée, rendant
indistinguables « en-tête malformé, à rejeter tout de suite » et « signature qui ne correspond
pas à ce secret-là, à réessayer ».

## La capture

`LeadCaptureService` construit la liste des secrets acceptables : le courant, plus le précédent
si `previous_hmac_secret` n'est pas nul **et** que `previous_secret_expires_at` est postérieure à
l'instant courant. Il pose ensuite `signedWithPreviousSecret` sur la ligne `RawLeadEvent` à
partir du drapeau rendu par le vérificateur.

C'est le seul endroit qui connaisse la notion de fenêtre : il tient déjà le `Client`, il a déjà
l'instant, et il écrit déjà la ligne.

## L'API d'administration

**`POST /api/admin/clients/{id}/rotate-secret`** (rotation, route existante) rend désormais
`SecretRotated(String hmacSecret, Instant ancienSecretValideJusquA)`. L'écran a besoin des deux
au même instant : le nouveau secret à copier, et la date jusqu'à laquelle l'ancien tient encore.

**`GET /api/admin/clients/{id}`** (fiche) gagne un champ **nullable** `TransitionSecret
transition`, record de deux valeurs : `expireLe` et `dernierLeadAncienSecret` (nul si aucun lead
n'a été signé avec l'ancien secret). `null` quand aucune transition ne court — l'état de toutes
les boutiques aujourd'hui, et il ne demande aucune interprétation. **Aucun secret n'y figure**,
pas plus le précédent que le courant : la fiche n'en a jamais rendu, et une transition n'est pas
une raison de commencer.

**`POST /api/admin/clients/{id}/revoke-previous-secret`** (révocation) met les deux colonnes à
`null` et rend la fiche. Un `POST` sur une sous-ressource nommée par son geste, comme
`rotate-secret`, `activate` et `deactivate` : c'est la convention déjà tenue par
`ClientAdminController`, et un `DELETE` sur une colonne que l'API n'expose pas décrirait mal ce
qui se passe. La révocation d'une transition inexistante est un succès sans effet —
l'état visé est atteint, et un `404` obligerait l'écran à distinguer deux cas identiques pour
l'opérateur.

**Une frontière de package à ne pas casser.** `dernierLeadAncienSecret` se lit dans
`raw_lead_event`, qui appartient à `capture/`, alors que la fiche est servie par `tenant/`.
Plutôt que d'ouvrir un repository sur cette table depuis `tenant/`, `capture/` expose une petite
interface de lecture — `UsageAncienSecret.dernierUsage(UUID clientId)` — que `ClientAdminService`
injecte. Chaque package continue de posséder sa table, et `tenant/` ne sait pas où
l'information est rangée.

## L'écran

Trois changements sur la fiche existante (`features/boutiques/boutique-detail`), aucun écran
nouveau. Le dashboard reste à onze écrans.

**Le dialogue de rotation change de texte, et c'est le vrai livrable de la feature.** Il annonce
aujourd'hui que « le secret actuel sera définitivement perdu » — ce qui devient faux. Il dira que
l'ancien secret continue d'être accepté jusqu'à telle date, et que la boutique a jusque-là pour
mettre son site à jour. **Quand une transition court déjà**, il ajoute l'avertissement
correspondant : le secret d'origine cessera immédiatement de valoir.

**Un bandeau de transition**, visible tant que `transition` n'est pas `null` : « Ancien secret
encore accepté jusqu'au 6 septembre à 14 h 30 », et en dessous l'état de migration — soit
« dernier lead signé avec l'ancien secret il y a 2 h », soit « plus aucun lead signé avec
l'ancien secret », qui est le feu vert pour révoquer. **Le bouton de révocation vit dans ce
bandeau et nulle part ailleurs** : hors transition, il n'aurait rien à révoquer.

**`secret-revele` reprend la date.** Le composant montre déjà le secret une seule fois et exige
un accusé de réception explicite ; c'est précisément là que l'opérateur a besoin de savoir
combien de temps il lui reste.

Conformément à la règle du projet, ces trois éléments passent par les skills du plugin
`ui-ux-pro-max` **avant** d'écrire le code, jamais en relecture.

## Ce qui l'éprouve

**Le vérificateur d'abord**, où tout se joue et où les tests ne demandent aucun conteneur : une
signature valide sous le secret courant rend `secretPrecedent = false` ; une signature valide
sous le précédent est acceptée et rend `true` ; une signature fausse sous les deux lève la même
exception qu'aujourd'hui ; un en-tête malformé lève **avant toute comparaison**, ce qui se
vérifie en passant une liste de deux secrets et en n'observant qu'un seul refus.

**Puis la capture** : fenêtre ouverte, un lead signé à l'ancien est accepté et sa ligne porte le
drapeau ; **fenêtre expirée d'une seconde, le même lead est refusé en `401`**. C'est le test qui
prouve que l'expiration existe, et il sera **éprouvé en neutralisant la comparaison de date pour
le voir échouer** — la leçon du faux verrou de la borne haute de F13 : un test qu'on n'a pas vu
échouer ne prouve rien.

**Un test existant affirme le contraire de la feature, et doit changer.**
`RotationDesClesTest.apresRotationLAncienSecretNeSignePlus` envoie un lead signé avec l'ancien
secret juste après la rotation et attend un `401`. C'est précisément le défaut réparé : il
devient un `202`, et le `401` se vérifie après révocation ou après expiration. Le renommer
plutôt que le supprimer — la garantie « l'ancien secret finit par mourir » reste, seule sa date
change.

**Puis la rotation** : la première pose les deux colonnes ; la seconde, pendant la fenêtre,
remplace le précédent par celui qu'on vient de retirer ; la révocation les vide. Et un test
d'API asserte sur le **corps JSON** — jamais sur le DTO — qu'aucun secret ne sort de la fiche.

**Côté frontend** : le bandeau paraît et disparaît avec `transition`, et le dialogue dit ce qu'il
faut dans les deux cas.

## Hors périmètre

- **Aucune notification à la boutique** que son secret a tourné : elle n'a pas de compte sur la
  console, comme le commercial de F12. C'est l'opérateur de l'agence qui la prévient.
- **Aucun rattrapage des leads déjà refusés** avant la feature : ils n'ont jamais été écrits.
- **Aucune rotation automatique ni expiration programmée du secret courant.** F14 rend la
  rotation manuelle indolore ; la rendre périodique est une autre décision.
- **Le partage entre instances** reste hors sujet : la fenêtre vit en base, donc elle est déjà
  correcte à plusieurs instances, contrairement au limiteur de débit et à `PendingEventRelay`.
