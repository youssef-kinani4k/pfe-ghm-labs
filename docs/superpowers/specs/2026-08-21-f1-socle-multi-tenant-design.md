# F1 — Socle et multi-tenant

**Date :** 2026-08-21
**Statut :** validé
**Branche :** `feature/f1-socle-multi-tenant`
**Plan directeur :** `2026-08-21-leadflow-plan-general-design.md`, section 3, feature F1

---

## 1. Objectif

Poser le modèle de données complet de LeadFlow et la notion de client, de façon que les
features suivantes n'aient plus à toucher au schéma de base. F1 ne livre aucun
comportement au runtime : ni endpoint, ni consommateur de file, ni adaptateur ERP, ni
écran. Elle livre un schéma, les entités et repositories correspondants, le chiffrement
des secrets, et un jeu de données de démonstration utilisable en développement.

Le plan directeur identifie F1 comme la feature où le temps investi rapporte le plus :
un modèle bâclé impose de rouvrir les migrations pendant F3 et F4.

## 2. Contexte et contradictions levées

L'exploration du dépôt a révélé que la configuration existante est **mono-client** sur
trois points, alors que la décision 2.1 du plan directeur pose le multi-tenant dès la
première feature :

| Élément existant | État | Conflit |
| --- | --- | --- |
| `WebhookProperties.hmacSecret` | un secret global | chaque client a le sien |
| `CrmProperties.defaultProvider` | un fournisseur global | chaque client vise son ERP |
| `CrmProperties.Provider.baseUrl` et `apiKey` | une instance par fournisseur | deux clients sur Dolibarr ont chacun leur serveur |

Le troisième point n'était pas anticipé par le plan directeur et il est le plus
structurant : `CrmConnector.sync(CrmLead)` ne peut pas savoir vers quelle instance
pousser. F1 corrige les trois.

## 3. Décisions

Ces décisions ont été prises en session de conception et ne sont pas à re-litiger pendant
l'implémentation.

### 3.1 Toute la configuration propre au client vit en base

La ligne `client` porte le secret HMAC, l'identifiant du fournisseur ERP et les paramètres
de connexion à l'instance ERP de ce client. `application.yml` ne conserve que les réglages
qui ne dépendent pas du tenant.

Conséquence : ajouter un client est une insertion en base, pas un redéploiement. C'est ce
qui rendra la gestion des clients possible depuis le dashboard en F6.

### 3.2 Les secrets sont chiffrés au repos

La base contient désormais les secrets HMAC de tous les clients et les clés d'API de leurs
ERP. Un dump exposerait l'ensemble. Le chiffrement est applicatif, en AES-256-GCM, avec
une clé maître fournie par variable d'environnement et jamais stockée en base.

Le chiffrement côté PostgreSQL via `pgcrypto` a été écarté : la clé transiterait dans les
requêtes SQL et apparaîtrait dans les journaux de requêtes.

Un secret HMAC ne peut pas être haché : la vérification de signature exige de recalculer
le HMAC avec le secret original, qui doit donc rester récupérable.

### 3.3 Clés primaires en UUID ordonné dans le temps

Toutes les tables utilisent un `UUID` généré côté application par Hibernate, en variante
ordonnée dans le temps pour éviter la fragmentation d'index propre à l'UUID aléatoire.

Deux raisons : `raw_lead_event` est déjà livré en UUID par `V1`, donc une seule règle
gouverne tout le schéma ; et l'identifiant existe avant l'insertion, ce dont la couche
capture aura besoin en F2 pour publier sur RabbitMQ sans aller-retour en base.

### 3.4 Le webhook identifie le client par une clé publique dans l'URL

Le webhook doit savoir quel client émet avant de pouvoir choisir le secret de
vérification. Le client est donc désigné par un segment d'URL,
`POST /api/webhooks/leads/{clientKey}`, adossé à une colonne `public_key` unique et
distincte de la clé primaire.

Cette clé n'est pas un secret — c'est la signature qui authentifie. La séparer de la clé
primaire permet de la révoquer ou de la faire tourner sans recréer la ligne `client` ni
les données qui la référencent, et fait apparaître le tenant dans les journaux d'accès et
les métriques par route sans avoir à analyser le corps de la requête.

### 3.5 Les entités vivent dans le package de l'étape qui les produit

`CLAUDE.md` pose que les sous-packages sont des étapes du pipeline et non des couches
techniques. Un package `domain/` regroupant toutes les entités violerait cette règle.

`Client` et `SalesRep` ne sont produits par aucune étape : ce sont des données de
référence transverses, d'où un nouveau package `tenant/`. Les trois autres entités
rejoignent l'étape qui les écrit.

### 3.6 Périmètre et peuplement

F1 reste une feature de modèle de données. La gestion des clients par interface arrive
avec le dashboard en F6. Pour que F2 soit testable à la main dès sa première ligne, F1
ajoute un jeu de données de démonstration chargé uniquement sous le profil `dev`.

## 4. Schéma de données

### 4.1 `client`

| Colonne | Type | Contraintes |
| --- | --- | --- |
| `id` | `UUID` | clé primaire |
| `public_key` | `VARCHAR(64)` | `NOT NULL`, `UNIQUE` |
| `name` | `VARCHAR(160)` | `NOT NULL` |
| `hmac_secret` | `TEXT` | `NOT NULL`, chiffré |
| `crm_provider_id` | `VARCHAR(40)` | `NOT NULL` |
| `crm_config` | `TEXT` | `NOT NULL`, JSON chiffré |
| `assignment_strategy` | `VARCHAR(32)` | `NOT NULL`, défaut `ROUND_ROBIN`, `CHECK` |
| `scoring_config` | `JSONB` | `NOT NULL`, défaut `{}` |
| `active` | `BOOLEAN` | `NOT NULL`, défaut vrai |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL`, défaut `now()` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL`, défaut `now()` |

`crm_provider_id` contient la clé résolue par `CrmConnectorRegistry` : `dolibarr`, `odoo`,
ou celle d'un ERP ajouté ultérieurement. Aucune contrainte de valeur n'est posée en base,
car la liste des fournisseurs est déterminée au runtime par les beans présents ; une
contrainte `CHECK` obligerait à écrire une migration pour ajouter un ERP, ce que
l'invariant 4 interdit.

`crm_config` est un document JSON contenant les paramètres de connexion à l'instance ERP
du client : URL de base, clé d'API, et pour Odoo la base et l'utilisateur. Le choix d'un
document plutôt que de colonnes plates est motivé en section 6.1.

`assignment_strategy` prend `ROUND_ROBIN`, `GEOGRAPHIC` ou `SECTOR`. La colonne est
alimentée par F1 et consommée par F4.

`scoring_config` est créé sans schéma figé. Sa forme est définie par F3, quand le moteur
qui le lit sera écrit. Il n'est pas chiffré : ce sont des règles métier, pas un secret,
et le garder en `JSONB` préserve la possibilité de requêter dessus si le besoin apparaît.

### 4.2 `sales_rep`

| Colonne | Type | Contraintes |
| --- | --- | --- |
| `id` | `UUID` | clé primaire |
| `client_id` | `UUID` | `NOT NULL`, `REFERENCES client(id) ON DELETE CASCADE` |
| `full_name` | `VARCHAR(160)` | `NOT NULL` |
| `email` | `VARCHAR(255)` | `NOT NULL` |
| `crm_ref` | `VARCHAR(64)` | nullable |
| `sector` | `VARCHAR(80)` | nullable |
| `zone` | `VARCHAR(80)` | nullable |
| `active` | `BOOLEAN` | `NOT NULL`, défaut vrai |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL`, défaut `now()` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL`, défaut `now()` |

Contrainte `UNIQUE (client_id, email)` : deux clients distincts peuvent employer la même
personne, un même client ne peut pas la déclarer deux fois.

`crm_ref` est la référence du commercial dans l'ERP du client. Elle est nullable parce
qu'elle n'est pas connue tant qu'elle n'a pas été résolue ou saisie.

Index `(client_id, active)` pour la sélection des commerciaux éligibles en F4.

### 4.3 `raw_lead_event` — évolution

`ALTER TABLE raw_lead_event ADD COLUMN client_id UUID NOT NULL REFERENCES client(id)`.

La colonne est posée directement en `NOT NULL`, sans phase nullable ni reprise de
données : la table est vide et le projet n'est pas déployé.

Index ajouté : `(client_id, received_at DESC)`.

Les colonnes existantes de `V1` sont conservées telles quelles. `V1` n'est pas modifiée —
la modifier ferait échouer Flyway au démarrage sur une somme de contrôle divergente.

La colonne `source` n'est pas rendue redondante par `client_id` et n'est donc pas
supprimée : `client_id` désigne le tenant, tandis que `source` identifie le canal d'où
provient l'événement chez ce tenant, par exemple le formulaire de contact ou celui de
demande de devis. Un même client peut alimenter plusieurs canaux.

### 4.4 `lead`

| Colonne | Type | Contraintes |
| --- | --- | --- |
| `id` | `UUID` | clé primaire |
| `client_id` | `UUID` | `NOT NULL`, `REFERENCES client(id)` |
| `raw_event_id` | `UUID` | `NOT NULL`, `UNIQUE`, `REFERENCES raw_lead_event(id)` |
| `company_name` | `VARCHAR(160)` | nullable |
| `first_name` | `VARCHAR(80)` | nullable |
| `last_name` | `VARCHAR(80)` | nullable |
| `email` | `VARCHAR(255)` | `NOT NULL` |
| `phone` | `VARCHAR(32)` | nullable |
| `message` | `TEXT` | nullable |
| `detected_intent` | `VARCHAR(64)` | nullable |
| `intent_source` | `VARCHAR(32)` | nullable, `CHECK` |
| `score` | `INTEGER` | `NOT NULL`, défaut zéro |
| `status` | `VARCHAR(32)` | `NOT NULL`, `CHECK` |
| `assigned_sales_rep_id` | `UUID` | nullable, `REFERENCES sales_rep(id)` |
| `country_code` | `VARCHAR(2)` | nullable |
| `sector` | `VARCHAR(80)` | nullable |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL`, défaut `now()` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL`, défaut `now()` |

`raw_event_id` est unique. C'est le filet de sécurité de l'idempotence de la
qualification : un message rejoué depuis RabbitMQ ne peut pas produire un second lead, et
la garantie est portée par la base plutôt que par le code du consommateur.

`intent_source` prend `RULES` ou `GEMINI` et trace quel analyseur a répondu. Cette colonne
rend le basculement en mode dégradé observable au lieu d'être seulement affirmé, ce qui
sert autant au diagnostic qu'à la démonstration en soutenance.

`status` prend `QUALIFIED`, `ROUTED`, `SYNCED`, `REJECTED` ou `FAILED`. Le lead naît
`QUALIFIED` puisque c'est la qualification qui l'écrit ; `REJECTED` couvre le doublon et
les données invalides, `FAILED` un échec de traitement en aval.

Index : `(client_id, email, created_at DESC)` pour la déduplication de F3, et
`(client_id, status)` pour les compteurs du dashboard de F6.

### 4.5 `crm_sync_attempt`

| Colonne | Type | Contraintes |
| --- | --- | --- |
| `id` | `UUID` | clé primaire |
| `lead_id` | `UUID` | `NOT NULL`, `REFERENCES lead(id) ON DELETE CASCADE` |
| `provider_id` | `VARCHAR(40)` | `NOT NULL` |
| `status` | `VARCHAR(32)` | `NOT NULL`, `CHECK` |
| `account_ref` | `VARCHAR(64)` | nullable |
| `contact_ref` | `VARCHAR(64)` | nullable |
| `opportunity_ref` | `VARCHAR(64)` | nullable |
| `task_ref` | `VARCHAR(64)` | nullable |
| `error_message` | `TEXT` | nullable |
| `attempted_at` | `TIMESTAMPTZ` | `NOT NULL`, défaut `now()` |

`status` prend `SUCCESS` ou `FAILED`.

La table est **append-only** : chaque tentative ajoute une ligne, aucune n'est modifiée.
F5 relit la dernière tentative réussie d'un lead pour savoir quelles entités existent déjà
dans l'ERP et ne pas les recréer. C'est le support de sa stratégie d'idempotence.

`provider_id` est porté par la ligne et non déduit du client : si un client change d'ERP,
l'historique de synchronisation reste lisible.

Les références sont typées en `VARCHAR` et non en entier, comme `CrmSyncResult` : Dolibarr
et Odoo renvoient des entiers, d'autres ERP renvoient des UUID.

Index `(lead_id, attempted_at DESC)`.

### 4.6 Représentation des énumérations

Tous les statuts sont des `VARCHAR(32)` assortis d'une contrainte `CHECK`, mappés côté
Java en `@Enumerated(EnumType.STRING)`. Les types `ENUM` natifs de PostgreSQL ont été
écartés : les faire évoluer par migration est nettement plus contraignant, et ils
compliquent le mapping Hibernate sans bénéfice ici.

## 5. Organisation du code

```
tenant/          Client, SalesRep, ClientRepository, SalesRepRepository,
                 AssignmentStrategyType, package-info.java
capture/         RawLeadEvent, RawLeadEventRepository, RawLeadEventStatus
qualification/   Lead, LeadRepository, LeadStatus, IntentSource
crm/             CrmSyncAttempt, CrmSyncAttemptRepository, CrmSyncAttemptStatus
common/          BaseEntity, SecretCipher, EncryptedStringConverter, EncryptedJsonConverter
config/          SecurityProperties (nouveau), CrmProperties (allégé),
                 WebhookProperties (allégé)
```

`common/` accueille le chiffrement et la classe de base sans amender sa raison d'être :
son `package-info.java` annonce déjà les types de base des entités JPA et les utilitaires
partagés.

`BaseEntity` est un `@MappedSuperclass` portant `id`, `created_at` et `updated_at`,
mutualisés entre les cinq entités.

Le nouveau package `tenant/` reçoit son propre `package-info.java`, conformément à la
convention du projet.

## 6. Chiffrement des secrets

### 6.1 Pourquoi `crm_config` est un document et non des colonnes

Des colonnes plates — `crm_base_url`, `crm_api_key`, `crm_database`, `crm_username` —
casseraient l'invariant 4 du plan directeur : ajouter un ERP réclamant un réglage inédit
imposerait une migration et une modification de l'entité, alors que l'invariant promet un
sous-package, une classe et une entrée de configuration.

Un document opaque déporte la connaissance des clés dans l'adaptateur, qui valide au
démarrage ce dont il a besoin. C'est exactement la philosophie déjà retenue pour
`CrmProperties.Provider`, dont la documentation précise que tous les champs ne concernent
pas tous les ERP.

Le prix à payer est assumé : chiffré, le document est stocké en `TEXT` et n'est donc pas
requêtable en SQL. Aucun cas d'usage ne demande de chercher un client par son URL ERP.

### 6.2 Mécanisme

- **`SecretCipher`** — AES-256-GCM. La clé maître est lue depuis la variable
  d'environnement `LEADFLOW_MASTER_KEY`, attendue en base64 sur 32 octets, exposée par un
  record `SecurityProperties` sous `config/` conformément à la convention des propriétés
  typées. Un vecteur d'initialisation aléatoire de 12 octets est tiré à chaque écriture et
  stocké en tête du chiffré, sous la forme base64 de la concaténation du vecteur, du
  chiffré et du tag d'authentification.
- **`EncryptedStringConverter`** — `AttributeConverter<String, String>`, appliqué à
  `hmac_secret`.
- **`EncryptedJsonConverter`** — `AttributeConverter<Map<String, String>, String>`,
  appliqué à `crm_config` : sérialisation JSON puis chiffrement.

Le vecteur d'initialisation étant aléatoire, chiffrer deux fois la même valeur produit
deux résultats différents. C'est le comportement attendu, et il confirme qu'aucune
recherche par valeur chiffrée n'est possible.

### 6.3 Le risque de câblage

Un `AttributeConverter` est normalement instancié par Hibernate via son constructeur sans
argument, auquel cas `SecretCipher` serait nul et toute écriture échouerait. Le montage
fonctionne parce que Spring Boot installe `SpringBeanContainer` dans Hibernate, ce qui
permet à un converter annoté `@Component` de recevoir ses dépendances par injection.

C'est un comportement implicite sur lequel la feature s'appuie. Il est donc couvert par un
test d'intégration explicite, décrit en section 10.

## 7. Changement du port `CrmConnector`

`sync(CrmLead)` suppose une instance ERP unique connue par configuration. Cette hypothèse
tombe avec le multi-tenant. Nouvelle signature :

```java
CrmSyncResult sync(CrmLead lead, CrmTarget target);
```

Nouveau record `crm/model/CrmTarget(String providerId, Map<String, String> settings)`. Il
est délibérément neutre : aucun champ `baseUrl` en dur, car rien ne garantit qu'un ERP
futur s'adresse par URL. La traduction des clés reste dans l'adaptateur, comme l'exige
l'invariant 1.

Aucun adaptateur n'existe à ce jour, donc ce changement ne casse rien. C'est précisément
la raison de le faire maintenant : en F5, il coûterait la réécriture de deux adaptateurs.

`CrmConnectorRegistry.defaultConnector()` devient du code mort, puisque tout lead
appartient à un client et que tout client nomme son fournisseur. La méthode est supprimée,
ainsi que la propriété `leadflow.crm.default-provider` qu'elle lisait. `forProvider()` et
`availableProviders()` sont conservées.

## 8. Configuration

| Propriété | Devenir |
| --- | --- |
| `leadflow.webhook.hmac-secret` | supprimée, portée par `client.hmac_secret` |
| `leadflow.webhook.signature-header` | conservée |
| `leadflow.webhook.tolerance` | conservée |
| `leadflow.crm.default-provider` | supprimée, portée par `client.crm_provider_id` |
| `leadflow.crm.providers.*.base-url` | supprimée, portée par `client.crm_config` |
| `leadflow.crm.providers.*.api-key` | supprimée, portée par `client.crm_config` |
| `leadflow.crm.providers.*.database` | supprimée, portée par `client.crm_config` |
| `leadflow.crm.providers.*.username` | supprimée, portée par `client.crm_config` |
| `leadflow.crm.providers.*.password` | supprimée, portée par `client.crm_config` |
| `leadflow.crm.providers.*.enabled` | conservée, réglage technique |
| `leadflow.crm.providers.*.connect-timeout` | conservée, réglage technique |
| `leadflow.crm.providers.*.read-timeout` | conservée, réglage technique |
| `leadflow.security.master-key` | nouvelle, lue depuis `LEADFLOW_MASTER_KEY` |

`CrmProperties.Provider` passe ainsi de huit champs à trois.

## 9. Migrations et jeu de démonstration

- **`V2__multi_tenant_schema.sql`** crée `client`, puis `sales_rep`, puis applique
  l'`ALTER TABLE` sur `raw_lead_event`, puis crée `lead` et `crm_sync_attempt`. Cet ordre
  est imposé par les clés étrangères.
- **`db/dev/R__demo_data.sql`** insère un client de démonstration et ses commerciaux. Le
  dossier `db/dev` est ajouté à `spring.flyway.locations` uniquement sous le profil `dev` ;
  la production ne le voit jamais. La migration est répétable pour se rejouer sans conflit
  de version.

Le secret HMAC du client de démonstration étant chiffré, il ne peut pas être écrit en clair
dans du SQL. Le jeu de démonstration insère donc une valeur **déjà chiffrée**, produite une
fois avec une clé maître de développement fixée par défaut dans le profil `dev`. Sans cela,
aucune requête de test ne pourrait être signée en F2.

## 10. Tests

Les tests de persistance s'exécutent sur le conteneur PostgreSQL de
`TestcontainersConfiguration`, avec `@AutoConfigureTestDatabase(replace = NONE)`. Une base
embarquée est exclue : le schéma repose sur `JSONB`, `TIMESTAMPTZ` et des contraintes
`CHECK`, c'est-à-dire précisément ce qu'un moteur de compatibilité représenterait mal.

**Chiffrement, en test unitaire.** Aller-retour chiffrement puis déchiffrement ; deux
chiffrements de la même valeur produisent des résultats différents ; un chiffré altéré d'un
octet est rejeté et non déchiffré silencieusement, ce que garantit le tag d'authentification
GCM.

**Converters, en test d'intégration.** Écriture puis relecture d'un `Client` via
`ClientRepository`, avec vérification en SQL brut que la colonne `hmac_secret` ne contient
pas la valeur en clair. Ce test couvre le risque de câblage décrit en section 6.3.

**Contraintes, en test d'intégration.** Chaque contrainte est vérifiée par un test qui la
viole délibérément : deux `sales_rep` de même adresse chez le même client, deux `lead` sur
le même `raw_event_id`, un `sales_rep` référençant un client inexistant.

**Cohérence entités et schéma.** Elle est acquise sans test dédié : `ddl-auto: validate`
s'exécute au démarrage du contexte Spring, donc le chargement de `BackendApplicationTests`
échoue dès qu'une entité diverge d'une colonne.

## 11. Critères de recette

1. `docker compose down -v` suivi d'un démarrage applique `V1` puis `V2` sur une base
   vierge sans intervention manuelle.
2. `./mvnw verify` passe, daemon Docker démarré.
3. Sous le profil `dev`, le client de démonstration est présent et une lecture directe de
   la colonne `hmac_secret` ne laisse lire aucun secret en clair.
4. `CrmConnectorRegistry.forProvider()` fonctionne après le retrait de
   `defaultConnector()`.
5. `CLAUDE.md` reflète le nouveau package `tenant/`, la nouvelle signature de
   `CrmConnector`, les propriétés de configuration supprimées et l'existence du
   chiffrement.

Le cinquième critère n'est pas cosmétique. La norme 5.1 du plan directeur pose que chaque
session repart à froid : si `CLAUDE.md` continue d'annoncer `sync(CrmLead)` et un secret
HMAC global, la session F5 travaillera sur une information fausse.

## 12. Hors périmètre

F1 ne livre aucun endpoint REST, aucun consommateur RabbitMQ, aucun adaptateur ERP et
aucun écran. La gestion des clients par interface appartient à F6. La forme du document
`scoring_config` est définie par F3. La résolution de `crm_ref` pour les commerciaux
appartient à F4 et F5.

## 13. Risques

**Le câblage du converter par Spring dans Hibernate** est un comportement implicite. S'il
cessait de fonctionner, l'échec se produirait à la première écriture d'un secret. Le test
d'intégration de la section 10 le détecte au sein de F1 plutôt qu'en F2.

**La perte de la clé maître rend les secrets irrécupérables.** C'est le corollaire assumé
du chiffrement. En développement, la clé par défaut du profil `dev` évite le problème ; en
production, la clé doit être sauvegardée hors de la base.

**`crm_config` n'est pas validé par le schéma.** Une clé mal orthographiée ne sera
détectée qu'au démarrage de l'adaptateur, en F5. C'est le prix de la généricité exigée par
l'invariant 4, et la validation par adaptateur est déjà la règle du projet.

## 14. Prochaine étape

Produire le plan d'implémentation détaillé de F1 avec la compétence `writing-plans`, puis
l'exécuter sur la branche `feature/f1-socle-multi-tenant`.
