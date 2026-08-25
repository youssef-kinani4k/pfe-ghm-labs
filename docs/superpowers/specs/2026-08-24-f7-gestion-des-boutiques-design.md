# F7 — Gestion des boutiques : conception

## Le problème

À la fin de F6, ajouter une boutique cliente demande une insertion SQL dans la table
`client`, précédée du chiffrement manuel de `hmac_secret` et de `crm_config` avec la clé
maîtresse de l'instance. Il n'existe ni écran, ni commande, ni documentation de ce geste.

C'est disqualifiant. LeadFlow est destiné à une agence dont les utilisateurs ne sont pas
développeurs : une opération censée être courante — accueillir une nouvelle boutique — exige
aujourd'hui un accès `psql` et la connaissance d'une clé de chiffrement. La plateforme n'est
donc pas installable chez un client sans intervention technique à chaque ajout.

**Cette feature livre l'écran qui rend l'opération autonome.** Elle couvre le cycle de vie
complet d'une boutique et de ses commerciaux : création, modification, désactivation,
rotation des clés d'intégration, et vérification de l'accès à l'ERP.

## Ce qui est dans le périmètre

- Créer une boutique : identité, fournisseur ERP et ses paramètres, stratégie d'attribution.
- Gérer ses commerciaux : ajouter, modifier, désactiver.
- Désactiver et réactiver une boutique.
- Régénérer sa clé publique et son secret HMAC.
- Tester la connexion à son ERP **avant** d'enregistrer.

## Ce qui en est exclu, et pourquoi

**La suppression.** Trois tables référencent `client(id)` : `sales_rep` en `ON DELETE
CASCADE`, mais `raw_lead_event` et `lead` sans cascade. Supprimer une boutique ayant reçu un
seul lead serait refusé par Postgres, et l'écran rendrait une erreur technique
incompréhensible. La désactivation la remplace, et **aucun endpoint de suppression n'est
exposé** : ne pas offrir le geste vaut mieux que l'offrir et échouer.

**Les rôles.** Le dashboard reste une console d'agence à modèle d'utilisateur unique. Le
Javadoc de `DashboardUserDetailsService` a déjà tranché : introduire `ADMIN` / `CLIENT`
serait construire pour un besoin explicitement écarté. Tout opérateur connecté pourra donc
administrer les boutiques. Cette décision devra être revue le jour où plusieurs opérateurs
aux responsabilités différentes partageront la console.

**Le barème de scoring.** `scoring_config` pilote le comportement de qualification, et sa
lecture est délibérément tolérante — un document malformé retombe sur les défauts au lieu
d'échouer. Exposer des poids et des listes cibles sans rendre ce repli visible serait un
piège. Une boutique créée par l'écran part donc sur le barème par défaut.

**Une commande d'onboarding.** Écartée. L'interface est le chemin principal et suffit dès
qu'un compte opérateur existe, ce que la configuration garantit déjà.

## Architecture

### Le package d'accueil

Tout vit dans **`tenant/`**, jamais dans `monitoring/`.

C'est la conséquence directe de l'invariant central de F6 : `monitoring/` est un observateur
qui lit les tables des autres étapes par des repositories en lecture seule et n'écrit que sa
table `dead_letter`. `LeadQueryRepository` étend même `Repository` nu plutôt que
`JpaRepository`, pour qu'aucune méthode d'écriture ne soit exposée. Le CRUD des boutiques
écrit dans `client` et `sales_rep` : il n'a rien à faire là.

`monitoring/ClientDirectoryController`, qui sert l'annuaire en lecture pour alimenter les
filtres du dashboard, **reste inchangé**. Deux chemins de lecture coexisteront sur les mêmes
tables, et c'est assumé : ils répondent à deux besoins distincts, et fusionner les deux
ferait entrer une écriture dans `monitoring/`.

`tenant/` gagne donc : un contrôleur d'administration, un service par agrégat (boutique,
commerciaux), un sous-package `dto/`, et les méthodes de repository qui manquent.
`ClientRepository` n'expose aujourd'hui que `findByPublicKeyAndActiveTrue`.

### Surface d'API

Préfixe `/api/admin/`, distinct de `/api/clients` déjà pris par l'annuaire de monitoring.

| Verbe et chemin | Rôle |
| --- | --- |
| `GET /api/admin/clients` | Liste : nom, fournisseur, stratégie, état, nombre de commerciaux actifs |
| `GET /api/admin/clients/{id}` | Fiche complète, **sans jamais le secret** |
| `POST /api/admin/clients` | Création — seule réponse, avec la rotation, à porter le secret en clair |
| `PUT /api/admin/clients/{id}` | Identité, fournisseur, paramètres ERP, stratégie |
| `POST /api/admin/clients/{id}/activate` | Réactivation |
| `POST /api/admin/clients/{id}/deactivate` | Le remplaçant de la suppression |
| `POST /api/admin/clients/{id}/rotate-secret` | Nouveau secret HMAC, rendu une seule fois |
| `POST /api/admin/clients/{id}/rotate-public-key` | Nouvelle clé publique |
| `GET /api/admin/clients/{id}/sales-reps` | Commerciaux de la boutique, actifs et inactifs |
| `POST /api/admin/clients/{id}/sales-reps` | Ajout d'un commercial |
| `PUT /api/admin/sales-reps/{id}` | Modification |
| `POST /api/admin/sales-reps/{id}/activate` et `/deactivate` | Cycle de vie du commercial |
| `POST /api/admin/crm/test` | Teste une cible ERP non enregistrée |

Trois décisions portent ce tableau.

**Aucune entité JPA ne franchit la frontière HTTP**, comme en F6. `Client` porte `hmacSecret`
et `crmConfig` **déchiffrés à la lecture** par les `AttributeConverter` : sérialiser l'entité
publierait le secret. Toute réponse passe par un `record` de `tenant/dto/`, et un test
l'asserte sur le corps JSON — pas sur le DTO, qui ne prouverait rien.

**Les actions sont des sous-ressources, pas des champs.** Rotation et désactivation ont des
conséquences distinctes — l'une casse la signature de la boutique, l'autre coupe sa capture.
Les noyer dans le `PUT` général les rendrait déclenchables par inadvertance, au fil d'une
simple correction de nom.

**Le test de connexion reçoit les paramètres dans le corps**, non enregistrés : c'est tout
l'intérêt de vérifier avant de sauver.

**La création crée la boutique et son premier commercial dans une seule transaction.**
`POST /api/admin/clients` accepte le commercial dans son corps. Deux appels séparés
laisseraient, en cas d'échec du second, exactement l'état que cette feature cherche à
empêcher : une boutique active sans commercial, dont les leads partent en DLQ.

### Le chiffrement reste invisible

Le service manipule des valeurs en clair ; `EncryptedStringConverter` et
`EncryptedJsonConverter` chiffrent à l'écriture et déchiffrent à la lecture. Rien de nouveau
n'est à écrire côté cryptographie, et le navigateur n'en voit rien : il envoie du clair sur
l'API interne, le serveur chiffre avant d'écrire.

## Le test de connexion à l'ERP

### Une quatrième méthode sur le port

`CrmConnector` expose aujourd'hui `providerId()`, `sync(...)` et `resolveAssignee(...)`. Elle
gagne :

```java
CrmCheck verifieAcces(CrmTarget cible);
```

**Chaque ERP a une sonde naturelle et bon marché.** Dolibarr expose `/status`, qui rend sa
version et valide d'un coup `baseUrl` et `apiKey`. Odoo n'a pas besoin d'endpoint dédié :
`authentifie(target)` est l'authentification JSON-RPC, qui valide `baseUrl`, `database`,
`username` et `apiKey` en un seul appel. Aucune des deux ne crée quoi que ce soit dans
l'ERP : le test est en lecture pure.

**La méthode est obligatoire, sans implémentation par défaut.** Un `default` rendant « non
vérifiable » laisserait un futur adaptateur dégrader silencieusement une promesse faite à
l'écran. Que le compilateur réclame la sonde est cohérent avec les trois gestes déjà
documentés pour ajouter un ERP.

### Un résultat neutre, pas un message brut

Pendant la recette de F6, un échec Dolibarr donnait ceci :

> `I/O error on POST request for "http://localhost:8081/api/index.php/thirdparties":
> Connection refused: getsockopt`

Inexploitable par un non-technicien. L'adaptateur rend donc une **cause typée** :

| Cause | Sens |
| --- | --- |
| `JOIGNABLE` | L'ERP répond et accepte les identifiants |
| `INJOIGNABLE` | Aucune réponse à cette adresse |
| `IDENTIFIANTS_REFUSES` | Le serveur répond mais rejette la clé ou le compte |
| `CIBLE_INCONNUE` | Base Odoo inexistante, ou racine d'API introuvable |
| `REPONSE_INATTENDUE` | Réponse illisible — souvent une URL qui pointe ailleurs |

L'écran traduit la cause en phrase ; le détail technique reste disponible, replié, pour le
diagnostic. Ce découpage préserve l'invariant le plus fragile du projet : **aucun terme
propre à un fournisseur ne remonte dans le modèle pivot**. C'est l'adaptateur qui sait qu'un
`401` Dolibarr et un refus JSON-RPC Odoo disent la même chose.

### Exécution

Le test passe par `CrmConnectorRegistry`, qui applique déjà le drapeau `enabled` : un
fournisseur désactivé dans `application.yml` doit le dire explicitement plutôt qu'échouer
obscurément. Il utilise les délais de `CrmHttpConfig` — 5 secondes de connexion, 15 de
lecture — donc un serveur muet fait patienter jusqu'à quinze secondes, avec un indicateur de
progression à l'écran.

**Le test rend `200` même quand il échoue.** `{ ok: false, cause: "IDENTIFIANTS_REFUSES" }`
est une réponse réussie à la question posée. Le traiter en `502` ferait passer l'intercepteur
du dashboard pour un incident et brouillerait le message.

**Une réserve de sécurité, assumée.** Cet endpoint fait émettre au serveur un appel HTTP vers
une URL fournie par l'opérateur. Sur une console interne authentifiée, c'est acceptable ; le
jour où le dashboard s'ouvrirait à des utilisateurs moins fiables, il faudrait restreindre
les destinations joignables.

## Les écrans

### Liste — `/boutiques`

Tableau Material : nom, fournisseur ERP, stratégie d'attribution, **nombre de commerciaux
actifs**, état, actions. Une entrée « Boutiques » s'ajoute au menu latéral.

Le compteur de commerciaux **se signale quand il vaut zéro**, comme « aucun consommateur » sur
l'écran File d'attente. Même intention : rendre visible la configuration qui casse
silencieusement le pipeline, avant que les leads ne meurent.

### Fiche — `/boutiques/:id`

Quatre blocs, dans l'ordre d'usage :

1. **Identité** — nom, stratégie d'attribution, état, bouton activer/désactiver.
2. **ERP** — fournisseur et ses champs propres, bouton « Tester la connexion », résultat du
   dernier test.
3. **Commerciaux** — table, ajout, désactivation.
4. **Intégration** — URL de webhook prête à copier, clé publique et sa rotation, et le secret
   — jamais affiché ici, seulement régénérable.

Les champs ERP **dépendent du fournisseur choisi** : `baseUrl` et `apiKey` pour Dolibarr ;
`baseUrl`, `database`, `username` et `apiKey` pour Odoo. Ces clés sont celles que les
adaptateurs interprètent, documentées dans `docs/erp-integration-setup.md`.

La fiche renvoie vers les écrans existants filtrés sur cette boutique — ses leads, ses
connecteurs — plutôt que de réafficher ces données.

### Création — `/boutiques/nouvelle`

La même page, en trois sections. Le bouton **« Créer la boutique » ne s'active qu'une fois la
connexion ERP testée avec succès et au moins un commercial saisi.**

C'est la garantie anti-boutique-cassée : sans commercial actif, le routage lève
`AssignmentException` et les leads partent en DLQ dès le premier formulaire soumis. Un
utilisateur non technique ne doit pas pouvoir fabriquer cet état.

### Le moment du secret

Un écran de confirmation à part, affiché après création ou rotation. Il montre le secret HMAC
et l'URL de webhook avec des boutons de copie, et exige un clic explicite — « J'ai transmis
ces informations » — pour disparaître. **Il ne réapparaîtra jamais** : perdu veut dire
régénéré, pas récupéré. Le dashboard ne devient pas un coffre à secrets consultable.

### Deux confirmations qui disent leur conséquence

Plutôt que « êtes-vous sûr ? » :

- désactiver une boutique annonce que **son formulaire recevra un `401` immédiatement** ;
- régénérer le secret annonce que **son formulaire cessera de fonctionner** tant qu'elle
  n'aura pas mis à jour son côté ;
- régénérer la clé publique annonce la même rupture, pour une autre raison : **l'URL du
  webhook change**, et l'ancienne devient inconnue. C'est précisément ce pour quoi la clé
  publique est distincte de la clé primaire — elle est révocable sans recréer la ligne.

### Le visuel

Les décisions visuelles passent par le plugin `ui-ux-pro-max`, invoqué **avant** d'écrire le
code d'interface, en réutilisant le système déjà consigné dans
`design-system/leadflow-dashboard/MASTER.md` : style Swiss, échelle d'espacement dense, Fira
Sans sur les titres, Fira Code sur les identifiants, couleurs de statut sémantiques.

## Règles métier et erreurs

Toutes les erreurs sont des `ProblemDetail` (RFC 7807), comme le reste de l'API.

**Les conflits sont tranchés par la base**, dans la même logique que l'idempotence de la
capture : l'unicité de `public_key` et celle de `(client_id, email)` sur les commerciaux sont
déjà des contraintes SQL. On les laisse arbitrer plutôt que de faire un « existe-t-il
déjà ? » préalable, que deux requêtes concurrentes passeraient toutes les deux. La violation
devient un `409` porteur d'une phrase lisible.

**Trois règles gardées côté serveur**, l'écran ne faisant que les refléter :

- désactiver le **dernier commercial actif** d'une boutique active est refusé, sinon ses leads
  partiraient en DLQ ;
- le `crm_provider_id` est validé **contre `CrmConnectorRegistry`**, jamais contre une liste
  écrite en dur : ajouter un ERP ne doit rien demander ici ;
- les clés attendues dans `crm_config` sont vérifiées selon le fournisseur avant écriture.

**Désactiver une boutique ne touche pas à ses commerciaux.** Ils gardent leur état, et la
réactivation restitue la configuration telle quelle. Coupler les deux ferait perdre, à la
première désactivation, l'information de qui était actif — et la règle du dernier commercial
actif ne s'applique donc qu'aux boutiques actives.

**La génération des clés.** Secret HMAC : 32 octets de `SecureRandom` en hexadécimal, soit le
format de 64 caractères déjà en usage. Clé publique : une valeur **opaque**, sans le nom de la
boutique — elle voyage dans une URL, et y inscrire le nom du client révélerait la liste des
clients à quiconque verrait passer un lien.

## Base de données

**Aucune migration.** Le schéma porte déjà tout : `public_key` unique, `(client_id, email)`
unique sur les commerciaux, `ON DELETE CASCADE` du commercial vers son client, et des valeurs
par défaut sur `assignment_strategy`, `scoring_config` et `active`. Une tâche qui semble
réclamer une `V5` a dévié de ce document.

## Tests

En TDD, avec les conventions en place : `@SpringBootTest` plus `MockMvc` et Testcontainers,
jamais `@DataJpaTest`, dont la tranche exclut les `@Component` que sont les converters
chiffrés.

Les cas qui comptent sont ceux qui **relient l'écran au pipeline** :

1. le secret n'apparaît **que** dans la réponse de création et de rotation — asserté sur le
   corps JSON de chaque autre endpoint ;
2. après rotation, une requête webhook signée avec l'**ancien** secret rend `401` — la preuve
   que la rotation agit réellement sur la capture ;
3. après désactivation d'une boutique, son webhook rend `401` ;
4. le refus de désactiver le dernier commercial actif ;
5. la sonde de chaque adaptateur contre `MockRestServiceServer` — succès, identifiants
   refusés, serveur injoignable ;
6. le `crm_provider_id` inconnu est refusé en `400`.

Côté frontend, Karma sur le service d'API — chemins et corps envoyés — et sur la règle
d'activation du bouton « Créer la boutique ».

Les 294 tests backend existants doivent rester verts. Sur la machine de développement, la
suite complète ne tient pas dans une seule invocation : elle se repasse en quatre lots —
`capture`+`common`+`crm`, `monitoring`, `qualification`, `routing`+`tenant`+
`BackendApplicationTests` — après nettoyage des JVM et conteneurs laissés par un run
interrompu.

## Documentation à produire

- `docs/boutique-onboarding.md` — accueillir une boutique de bout en bout : créer, tester
  l'ERP, ajouter le premier commercial, transmettre l'URL de webhook et le secret.
- `docs/monitoring-api.md` — la nouvelle surface `/api/admin/`.
- `CLAUDE.md` — la règle « le CRUD des boutiques vit dans `tenant/`, `monitoring/` reste un
  observateur », et la section « État actuel » mise à jour.

## Critères de recette

1. Créer une boutique complète depuis l'interface, **sans ouvrir la base**, et voir son
   premier lead traverser le pipeline jusqu'à l'ERP.
2. Une clé d'API fausse est signalée par le test de connexion, en français, avant
   enregistrement.
3. Le bouton de création reste inactif tant qu'il manque le test ERP ou le commercial.
4. Le secret n'est visible qu'une fois ; aucune autre réponse de l'API ne le contient.
5. Après rotation du secret, l'ancien ne signe plus : le webhook rend `401`.
6. Une boutique désactivée voit son webhook rendre `401`, et se réactive sans perte.
7. Désactiver le dernier commercial actif est refusé, avec la raison affichée.
