# F14 livrée et revue, ni recettée ni fusionnée — état pour la reprise

Session du 5 septembre 2026. F14 est **complète, revue de bout en bout, et corrigée après sa
revue finale**. Elle vit sur `feature/f14-rotation-hmac`, **conservée telle quelle** :
ni fusionnée dans `main`, ni poussée. Les deux points ouverts sont la **recette à l'écran**,
qui revient à l'utilisateur, et la décision d'intégration.

## L'état du dépôt

| | |
| --- | --- |
| `main` | **`3249307`**, inchangée — la fusion de F13 |
| Branche de la feature | `feature/f14-rotation-hmac`, **14 commits**, non poussée |
| Arbre de travail | **propre** |
| Migrations | **douze** — `V11__hmac_secret_transition.sql` et `V12__previous_secret_since.sql` |
| Suite backend | **548 tests, 0 échec, 1 ignoré**, jouée en entier sur l'arbre final |
| Suite frontend | **81/81**, ESLint et Prettier verts |
| Écrans | toujours **onze** — F14 n'ajoute aucune route, seulement un bandeau sur la fiche d'une boutique |

## Ce que F14 livre

**Le défaut réparé.** `tourneLeSecret` remplaçait le secret HMAC à l'instant du commit, alors
que le site de la boutique continuait de signer avec l'ancien jusqu'à son redéploiement :
**chaque lead était refusé en `401`** entre les deux, indiscernable d'une attaque, et la
boutique ne s'en apercevait qu'en constatant que plus rien n'arrivait. Le geste de sécurité le
plus élémentaire du produit coûtait des leads.

Désormais, une rotation ouvre une **fenêtre de transition** pendant laquelle les deux secrets
sont acceptés — `leadflow.webhook.transition-secret`, défaut `24h`, **globale à l'instance**
comme le fuseau des séries de F13 et le relais SMTP de F12.

| Pièce | Ce qu'elle fait |
| --- | --- |
| `V11` + `V12` | `previous_hmac_secret` (chiffrée au repos), `previous_secret_expires_at`, `previous_secret_since` sur `client` ; `signed_with_previous_secret` sur `raw_lead_event` |
| `HmacSignatureVerifier` | accepte une **liste ordonnée** de secrets et rend `SignatureVerifiee(canonique, secretPrecedent)` |
| `LeadCaptureService` | construit cette liste et pose le drapeau — **seul endroit** qui connaisse la fenêtre |
| `ClientAdminService` | la rotation ouvre la fenêtre, `revoke-previous-secret` la ferme |
| La fiche | champ nullable `transition : { expireLe, dernierLeadAncienSecret }` |
| L'écran | dialogue de rotation réécrit, bandeau daté, bouton de révocation |

## Ce qu'il faut savoir avant d'y toucher

**Les trois colonnes de transition vont toujours ensemble** : posées par la rotation, effacées
par la révocation, dans la même transaction. Un secret précédent sans expiration serait un
second secret permanent — l'inverse de la feature.

**L'ordre des deux `set` de `tourneLeSecret` est la propriété la plus fragile du lot** :
l'ancien secret est copié dans `previousHmacSecret` **avant** que `setHmacSecret` ne l'écrase.
L'ordre inverse ferait porter la fenêtre par le nouveau secret ; tout continuerait de passer,
les tests resteraient verts, et la feature ne servirait à rien. Aucun test ne peut exprimer
cette propriété directement — seule la lecture la vérifie, et le Javadoc la nomme.

**Une seconde rotation pendant la fenêtre est autorisée**, et l'ancien devient celui qu'on
vient de retirer : **jamais plus de deux secrets vivants**. Le secret d'origine meurt alors
immédiatement, et le dialogue de rotation l'annonce avant de confirmer.

**L'expiration est paresseuse** : aucune tâche planifiée, aucune écriture sur `client` depuis
le chemin de capture. Une fenêtre close n'est pas balayée ; le secret précédent survit chiffré
jusqu'à la rotation suivante ou la révocation, sans être jamais accepté.

**Le vérificateur ignore le mot « transition »** : il reçoit une liste de secrets acceptables,
le courant en premier — donc le cas normal ne calcule qu'un seul HMAC — et l'analyse de
l'en-tête comme le contrôle de l'horodatage sont faits **une seule fois**, avant toute
comparaison de secret. Il ne connaît toujours ni la base, ni HTTP, ni l'horloge.

**Les cinq causes de refus rendent toujours le même `401`**, et la clé d'idempotence
`(client_id, signature)` de `V3` n'est pas affectée : la forme canonique est reconstruite à
partir de l'hexadécimal qui a répondu, donc un rejeu sous l'un ou l'autre secret porte la même
clé.

## Les défauts trouvés en chemin, et par quoi

Aucun n'aurait été vu par la seule lecture du plan.

**Le balayage de conflits, avant la première tâche.** Le plan nommait le composant du bandeau
`TransitionSecret`, nom déjà porté par le modèle TypeScript — deux symboles homonymes dans un
écran qui importe les deux. Renommé `BandeauTransition`.

**La revue de la tâche 5.** Le test censé prouver que le dernier retardataire est bien le plus
récent n'écrivait **qu'une seule** ligne marquée : il vérifiait le filtre, pas le sens du tri.
Corrigé et éprouvé en inversant `Desc` en `Asc` pour le voir rougir.

**La revue de la tâche 6.** Après une révocation, le panneau `secret-revele` continuait
d'affirmer « L'ancien secret reste accepté jusqu'au ... » avec une date devenue fausse — sur un
écran qui parle de secrets, une affirmation contredite est le pire des messages.

**La revue finale, et c'est le défaut le plus intéressant.** `dernierUsage` cherchait dans
**tout l'historique** de la boutique un lead marqué, alors que le drapeau est permanent et
décrit une fenêtre **passée**. Dès la **seconde** rotation d'une boutique, la fiche rendait la
date d'un retardataire vieux de plusieurs mois — antérieure à la rotation qu'elle décrivait — et
le message « Plus aucun lead signé avec l'ancien secret », qui est le feu vert pour révoquer et
la raison d'être du drapeau, ne pouvait plus jamais réapparaître. **Et le test certifiait le
défaut** : il écrivait son lead marqué *avant* la rotation, donc il passait aussi bien contre le
code faux que contre le code juste. Aucune revue par tâche ne pouvait le voir : il fallait
raisonner sur la deuxième rotation d'une boutique, six mois plus tard.

La correction retenue est la solution **exacte** et non l'approchée : `V12` ajoute
`previous_secret_since`, et la lecture borne la recherche à cette date. Dériver la borne par
`expireLe - transitionSecret` aurait suffi presque toujours, et serait devenu faux le jour où le
réglage de durée change entre la rotation et la lecture — or ce signal sert à décider de
révoquer un secret.

## La recette à l'écran — elle reste à faire

C'est le point ouvert de F14, avec la fusion.

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm start
```

1. Sur une boutique, régénérer le secret : le dialogue **annonce la fenêtre** au lieu de
   promettre que l'ancien secret est perdu, et l'écran révèle le nouveau secret **et** la date
   de fin.
2. Le bandeau de transition apparaît **sans recharger la page**.
3. Envoyer un lead signé avec l'**ancien** secret : il est accepté, et le bandeau passe à
   « Dernier lead signé avec l'ancien secret ».
4. Envoyer un lead signé avec le nouveau : accepté, le bandeau ne bouge pas.
5. Révoquer : le bandeau disparaît, la mention de la fenêtre disparaît aussi du panneau du
   secret, et un lead signé avec l'ancien secret est désormais refusé.
6. Régénérer deux fois de suite : le dialogue avertit que le secret d'origine meurt
   immédiatement.

## Quatre dettes nommées, volontairement non traitées

Aucune n'est un défaut observable ; les élargir aurait fait grossir une vague de correction déjà
assez large pour se tromper.

- **Le prédicat « fenêtre ouverte » est écrit à deux endroits** — `LeadCaptureService` et
  `ClientAdminService`. Ils s'accordent aujourd'hui, mais la spec affirme que la capture est le
  seul endroit à connaître la fenêtre, et ce n'est plus littéralement vrai. Une méthode
  `Client.transitionOuverte(Instant)` le remettrait en un seul point.
- **L'index ne porte pas le drapeau** : `idx_raw_lead_event_client_received` sert le tri, pas le
  filtre, donc le cas courant — aucun retardataire — parcourt la tranche d'index de la boutique.
  Le bornage de `V12` l'a largement réduit ; un index partiel serait la version complète.
- **`transitionSecret` n'est pas validée** : une valeur absente ferait une erreur serveur à la
  rotation, une valeur absurde annulerait en silence la contrainte « la fenêtre doit finir ».
- **Une fenêtre expirée n'a pas de sortie à l'écran** : le bandeau et son bouton disparaissent,
  et le secret périmé dort en base jusqu'à la rotation suivante. Il est inutilisable, donc c'est
  cosmétique — mais purger demande un appel d'API, ce qui frotte contre la règle du projet
  voulant qu'une opération courante passe par l'interface.

## La suite

- **F15 — pivot ERP en carte de références.** La dernière de la feuille de route, et la plus
  lourde : elle touche `crm/model`, les deux adaptateurs, `monitoring/dto` et le frontend, et
  ouvre la propagation d'une réattribution jusqu'à l'ERP.
- Puis, hors feuille de route : le TLS, le nonce CSP, le passage multi-instance, un second canal
  de notification, le recalcul rétroactif des scores.
- Et la dette laissée par F13, toujours ouverte : **le vrai bornage de la CTE des délais** de
  `SeriesRepository`.
