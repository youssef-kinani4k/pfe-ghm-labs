# F5 — Connecteurs ERP

Statut : validé en session de conception, prêt pour le plan d'implémentation.
Dépend de : F1 (livrée) pour `client`, `crm_sync_attempt`, le chiffrement et le port.
Position dans le planning général : deuxième feature exécutée, avant F2.

---

## 1. Objectif

Implémenter réellement les deux adaptateurs derrière `CrmConnector` — Dolibarr en REST,
Odoo en JSON-RPC — et l'orchestration minimale qui permet de les exercer de bout en bout :
résolution de l'instance ERP du client, reconstruction de l'état des tentatives
précédentes, écriture de la trace.

À la fin de F5, un lead présent en base peut être poussé vers l'ERP du client qui l'a
reçu, et un rejeu du même lead ne crée aucun doublon.

---

## 2. Contexte et contradictions levées

F5 passe délibérément avant F2, F3 et F4. C'est la seule couche dont le comportement dépend
d'API externes non maîtrisées ; la traiter tôt laisse le temps de réagir si Dolibarr ou
Odoo s'écartent de leur documentation.

Conséquence directe : **les couches qui appelleraient naturellement les connecteurs
n'existent pas encore**. Ni le webhook (F2), ni le consommateur de la file (F3), ni le
routage (F4). F5 doit donc livrer juste assez d'orchestration pour être testable seule,
sans empiéter sur ces features.

Trois points laissés ouverts par F1 sont tranchés ici, parce que F5 est la première feature
où ils deviennent réels :

- `CrmConnectorRegistry.forProvider(null)` lève aujourd'hui un `NullPointerException` nu au
  lieu du message diagnostique prévu — invisible tant que le registre est vide.
- `leadflow.crm.providers.<x>.enabled` n'a aucune sémantique : personne ne lit le drapeau.
- `CrmLead.assigneeRef` et `sales_rep.crm_ref` sont annotés « résolus en F4/F5 » sans que
  la frontière soit tranchée.

---

## 3. Décisions

### 3.1 Le port reçoit l'état antérieur plutôt que de lire notre base

```java
CrmSyncResult sync(CrmLead lead, CrmTarget target, CrmSyncState previous);
String resolveAssignee(CrmAssignee assignee, CrmTarget target);
```

Le planning général suggérait que l'adaptateur consulte `crm_sync_attempt` lui-même. C'est
écarté : cela obligerait chaque adaptateur à injecter un repository JPA, donc à connaître
notre schéma, et rendrait ses tests dépendants d'une base.

`CrmSyncState(accountRef, contactRef, opportunityRef)` est un record du modèle pivot, au
vocabulaire neutre comme le reste de `crm/model`. L'adaptateur reste une fonction de ses
arguments.

`resolveAssignee` est la seconde méthode du port. Traduire un commercial en identifiant ERP
est spécifique au fournisseur — `res.users` chez Odoo, l'utilisateur Dolibarr ailleurs — et
n'a pas sa place dans l'orchestrateur.

### 3.2 F5 livre l'orchestrateur `CrmSyncService`

Puisque l'appelant doit désormais fournir l'état antérieur, quelqu'un doit jouer ce rôle.
`crm/CrmSyncService` porte tout ce qui est propre à LeadFlow :

1. lit le `Client` du lead et en dérive `CrmTarget(crmProviderId, crmConfig déchiffré)` ;
2. relit `crm_sync_attempt` pour le couple (lead, provider) et reconstruit `CrmSyncState` ;
3. si `sales_rep.crm_ref` est nul, appelle `resolveAssignee` et mémorise le résultat ;
4. appelle `registry.forProvider(...).sync(...)` ;
5. écrit une ligne `crm_sync_attempt` dans tous les cas et positionne `lead.status`.

C'est aussi le premier endroit où l'on vérifie que le chiffrement de F1 alimente réellement
un adaptateur : `crm_config` déchiffré devient les `settings` de `CrmTarget`.

### 3.3 Le registre applique `enabled` et distingue trois refus

`CrmConnectorRegistry` injecte `CrmProperties`. `availableProviders()` ne liste que les
fournisseurs activés. `forProvider` distingue par des messages différents : identifiant
`null`, fournisseur inconnu, fournisseur connu mais désactivé. Un client configuré sur un
fournisseur désactivé doit produire un diagnostic exploitable, pas un
`NullPointerException`.

### 3.4 F5 résout et mémorise `sales_rep.crm_ref`

Le service réutilise `crm_ref` s'il est renseigné, sinon le résout via le port et l'écrit en
base. L'aller-retour n'a donc lieu qu'une fois par commercial et par instance ERP. F4 n'aura
plus qu'à **choisir** le commercial ; la traduction vers l'ERP est acquise.

### 3.5 Périmètre : compte, contact, opportunité

`sync` crée trois objets dans chaque ERP. `taskRef` reste nul — la colonne existe depuis F1,
l'ajouter plus tard ne coûte aucune migration. Créer une tâche de rappel assignée au
commercial est reporté : c'est un quatrième objet à traduire, tester et rendre idempotent
dans deux ERP, pour une valeur de démonstration marginale.

### 3.6 Une sonde précède l'écriture des adaptateurs

La première tâche de F5 est une exploration jetable contre les conteneurs réels, sur le
modèle du `DemoSecretGenerator` de F1 : relever la forme réelle des réponses, le nom exact
des champs, la procédure minimale pour obtenir une clé d'API Dolibarr et pour disposer du
module `crm` côté Odoo. **Son résultat est reporté dans cette spec avant qu'un adaptateur
soit écrit.** C'est la raison d'être de la position de F5 dans le planning ; l'ignorer
reviendrait à découvrir les écarts en écrivant les tests.

Elle sert aussi à épingler les images : `dolibarr/dolibarr:latest` et `odoo:17` deviennent
des versions figées. Pour une feature dont l'enjeu est la conformité d'API, un tag flottant
est un piège.

---

## 4. Modèle pivot — ajouts

Aucun terme propre à un fournisseur n'entre dans `crm/model`. Deux records s'ajoutent :

```java
public record CrmSyncState(String accountRef, String contactRef, String opportunityRef) { }

public record CrmAssignee(String fullName, String email) { }
```

`CrmLead`, `CrmTarget` et `CrmSyncResult` restent inchangés. `CrmSyncResult` porte déjà les
quatre références et l'horodatage.

---

## 5. Organisation du code

```
crm/
├── CrmConnector.java            port : providerId(), sync(...), resolveAssignee(...)
├── CrmConnectorRegistry.java    resolution par providerId, applique enabled
├── CrmSyncService.java          orchestration : cible, etat anterieur, trace
├── CrmSyncAttempt.java          trace append-only (F1)
├── model/                       CrmLead, CrmTarget, CrmSyncState, CrmAssignee, ...
├── dolibarr/
│   ├── DolibarrConnector.java   traduction pivot -> Dolibarr
│   └── DolibarrClient.java      transport REST, en-tete DOLAPIKEY
└── odoo/
    ├── OdooConnector.java       traduction pivot -> Odoo
    └── OdooClient.java          transport JSON-RPC, authenticate + execute_kw
```

Chaque adaptateur est en deux couches : la traduction ne fait pas de HTTP, le transport ne
connaît pas le modèle pivot. C'est ce qui rend la traduction testable sans réseau.

---

## 6. Traduction vers Dolibarr

REST sur `/api/index.php`, authentification par en-tête `DOLAPIKEY`.

| Pivot                                      | Dolibarr                                    |
| ------------------------------------------ | ------------------------------------------- |
| `companyName`                              | `POST /thirdparties`, `client: 2` (prospect) |
| `firstName`, `lastName`, `email`, `phone`  | `POST /contacts`, lié par `socid`           |
| opportunité                                | `POST /projects` avec les champs d'opportunité |
| `message`, `detectedIntent`, `score`       | note du tiers et de l'opportunité           |
| `assigneeRef`                              | utilisateur responsable de l'opportunité    |

Deux divergences résolues ici et nulle part ailleurs :

- **Dolibarr n'a pas d'objet « opportunité » de plein droit.** Ce qui s'en rapproche est le
  projet doté des champs d'opportunité. C'est le point de traduction le moins acquis, et la
  sonde doit le confirmer avant écriture.
- **Un lead sans société** : Dolibarr exige un tiers pour rattacher un contact. L'adaptateur
  crée alors un tiers au nom de la personne. Le pivot n'a pas à connaître cette contrainte.

---

## 7. Traduction vers Odoo

JSON-RPC sur `/jsonrpc` : `common.authenticate` pour obtenir l'`uid`, puis
`object.execute_kw` pour chaque opération.

| Pivot                        | Odoo                                                  |
| ---------------------------- | ----------------------------------------------------- |
| `companyName`                | `res.partner`, `is_company: true`                     |
| contact                      | `res.partner`, `is_company: false`, `parent_id`       |
| opportunité                  | `crm.lead`, `type: 'opportunity'`, `partner_id`, `email_from` |
| `score`                      | `priority` (échelle 0–3)                              |
| `detectedIntent`, `message`  | `description`                                         |
| `assigneeRef`                | `user_id`, résolu via `res.users`                     |

**Le piège à ne pas rater** : Odoo répond `HTTP 200` avec un objet `error` dans le corps
quand l'appel échoue. Un adaptateur qui se fie au code de statut avalerait silencieusement
les erreurs. `OdooClient` inspecte le corps avant tout et lève `CrmSyncException` en
reprenant le message Odoo.

`score` n'a d'équivalent que chez Odoo, sous forme de priorité ; côté Dolibarr il finit en
texte dans la note. Aucune de ces deux formes ne remonte dans `CrmLead`.

---

## 8. Idempotence et trace

`crm_sync_attempt` reste **append-only** : jamais d'`UPDATE`, invariant posé par F1. Chaque
appel écrit exactement une ligne, `SUCCESS` ou `FAILED`.

**Une ligne `FAILED` porte les références déjà obtenues.** Un échec sur l'opportunité laisse
donc remonter le tiers et le contact ; sans cela le rejeu les recréerait.

**Reconstruction de `CrmSyncState`** : pour le couple (`lead_id`, `provider_id`), on retient
**par champ la valeur non nulle la plus récente**, et non les champs de la dernière ligne.
Le repository de F1 fournit `findByLeadIdOrderByAttemptedAtDesc` ; il lui manque le filtre
par provider — un dérivé de plus, aucune migration.

La clé est bien (lead, provider) : un même lead peut partir vers des fournisseurs
différents, et des références Dolibarr ne doivent jamais servir d'état de départ à Odoo.

**Dans l'adaptateur**, chaque étape dont la référence est déjà connue est sautée. C'est là,
et nulle part ailleurs, que se joue l'absence de doublon au rejeu.

`CrmSyncService` écrit la trace **puis relaie l'exception**. La reprise sur échec appartient
à la file — 3 tentatives puis DLQ, déjà configuré — et son consommateur arrive en F3. F5 ne
réimplémente aucun retry.

---

## 9. Configuration

Rien de nouveau sous `leadflow.*`. Les réglages techniques existants deviennent effectifs :

- `leadflow.crm.providers.<x>.connect-timeout` et `read-timeout` sont réellement appliqués
  au `RestClient` de chaque transport — aujourd'hui personne ne les lit ;
- `enabled` est appliqué par le registre (§3.3) ; `odoo.enabled` passe à `true` une fois
  l'adaptateur écrit.

L'URL et les identifiants de chaque instance restent dans `client.crm_config`, chiffrés.
Aucune variable d'environnement ERP ne réapparaît dans `.env.example`.

`CrmSyncException` porte le `providerId` et l'étape en échec, **jamais la clé d'API**.

---

## 10. Tests

### 10.1 Étage 1 — contractuel, joué à chaque `./mvnw test`

`MockRestServiceServer` branché sur le `RestClient.Builder`. C'est dans `spring-test` :
**aucune dépendance nouvelle**, et cela couvre aussi bien le REST de Dolibarr que le
JSON-RPC d'Odoo. Ces tests prouvent :

- le mapping champ par champ — le corps envoyé est asserté, pas seulement le code retour ;
- l'idempotence : avec un `CrmSyncState` complet, **aucune requête de création ne part** ;
- le piège Odoo : `200` plus un objet `error` produit une `CrmSyncException`, jamais un
  succès ;
- l'authentification : `DOLAPIKEY` présent, séquence `authenticate` puis `execute_kw` ;
- l'absence de secret dans les messages d'erreur.

### 10.2 Étage 2 — intégration réelle, à la demande

Classes `@Tag("erp")`, exclues de Surefire par défaut, activées par un profil Maven
(`./mvnw verify -Perp-it`) contre `docker compose --profile dolibarr --profile odoo up -d`.

Pas de Testcontainers ici : Dolibarr s'installe en plusieurs minutes et réclame l'activation
de son module REST. Le scénario de recette est celui du planning — synchroniser un lead,
**rejouer le même message**, vérifier côté ERP qu'il n'existe qu'un tiers pour cet email.

### 10.3 Étage 0 — la sonde

Voir §3.6. Jetable, supprimée avant la fin de la feature, son résultat consigné dans cette
spec.

---

## 11. Critères de recette

1. `./mvnw verify` passe sans conteneur ERP : les tests contractuels des deux adaptateurs
   sont verts et la suite reste rapide.
2. `./mvnw verify -Perp-it` avec les deux profils ERP montés : un lead de démonstration
   apparaît dans Dolibarr comme tiers, contact et opportunité, et dans Odoo comme
   `res.partner` société, `res.partner` contact et `crm.lead`.
3. **Rejeu** : relancer la synchronisation du même lead ne crée aucun doublon, et
   `crm_sync_attempt` porte deux lignes dont la seconde réutilise les mêmes références.
4. Une instance ERP injoignable produit une ligne `FAILED` avec un message exploitable, et
   l'exception remonte à l'appelant.
5. Un client dont le `crm_provider_id` est inconnu ou désactivé produit un message
   explicite.
6. `sales_rep.crm_ref` est renseigné après la première synchronisation et réutilisé ensuite.
7. Les images ERP de `docker-compose.yml` sont épinglées sur les versions effectivement
   testées.
8. CLAUDE.md décrit les deux adaptateurs et la nouvelle signature du port.

---

## 12. Hors périmètre

- La création d'une tâche ou d'un événement d'agenda (`taskRef` reste nul).
- Tout appel depuis un consommateur de file : F3 le câblera.
- Le choix du commercial : F4. F5 ne fait que traduire celui qu'on lui donne.
- La mise à jour d'objets ERP existants : F5 crée, ne modifie pas.
- Tout troisième ERP.

---

## 13. Risques

**La conformité des API à leur documentation n'est pas acquise** — c'est le risque que
l'ordre du planning cherche justement à révéler tôt. La sonde (§3.6) le convertit en
information avant qu'une ligne d'adaptateur soit écrite.

**L'obtention d'une clé d'API Dolibarr sur un conteneur neuf** est le point le plus incertain
de la feature : le module REST doit être activé et une clé générée. Si la procédure ne peut
pas être automatisée proprement, l'étage 2 des tests devient une procédure documentée plutôt
qu'un profil Maven — l'étage 1 reste intact.

**Odoo réclame une base initialisée et le module `crm`.** Même arbitrage.

**Le périmètre de l'orchestrateur peut déborder sur F3 et F4.** Garde-fou : `CrmSyncService`
ne choisit pas de commercial, ne consomme aucune file, ne réessaie pas.

---

## 14. Prochaine étape

Écrire le plan d'implémentation via la compétence `writing-plans`, sur la branche
`feature/f5-connecteurs-erp`. La sonde en est la première tâche.
