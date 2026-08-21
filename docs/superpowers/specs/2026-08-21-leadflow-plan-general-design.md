# LeadFlow — Plan général de développement

**Date :** 2026-08-21
**Statut :** validé
**Portée :** plan directeur du projet. Chaque feature décrite ici fera l'objet de sa
propre spec détaillée, écrite avant son implémentation.

---

## 1. Contexte

LeadFlow est un middleware asynchrone entre les canaux marketing (formulaires web des
sites clients) et les ERP/CRM du commerce. Un prospect capté sur un site client est
authentifié, mis en file, nettoyé, qualifié, scoré, attribué à un commercial, puis poussé
dans l'ERP cible du client concerné. Une agence supervise l'ensemble depuis un dashboard.

Le squelette technique existe déjà et compile de bout en bout : monorepo `frontend/`
(Angular 20) et `backend/` (Spring Boot 4.1 / Java 21), infrastructure locale Postgres +
RabbitMQ via `docker-compose.yml`, configuration Spring en place (topologie RabbitMQ avec
dead-letter queue, sécurité stateless, propriétés typées), port `CrmConnector` et modèle
pivot. **Aucune logique métier n'est implémentée.** Le présent plan couvre tout ce qui
reste.

### Contraintes

| Contrainte | Valeur |
|---|---|
| Durée disponible | 3 à 4 mois |
| Effectif | une personne |
| Cadre | projet de fin d'études, date de soutenance fixe |

---

## 2. Décisions structurantes

Ces décisions ont été prises lors de la session de conception et ne sont pas à
re-litiger dans les specs de feature.

### 2.1 Multi-clients dès le départ

L'agence gère plusieurs sites clients distincts. Chacun possède son propre secret HMAC,
son ERP cible, ses règles de scoring et son équipe commerciale. Le modèle de données porte
donc la notion de client dès la première feature.

Conséquence : deux clients peuvent viser des ERP différents — l'un Dolibarr, l'autre Odoo —
ce qui justifie pleinement l'abstraction `CrmConnector` déjà en place.

### 2.2 Aucun fournisseur externe câblé en dur

Le pipeline ne connaît ni Dolibarr, ni Odoo, ni Google. Il parle à des interfaces :

- `CrmConnector` pour les ERP, avec un adaptateur par fournisseur sous `crm/<provider>/`.
- `IntentAnalyzer` pour l'analyse d'intention, avec un adaptateur règles et un adaptateur
  Gemini.

Cette symétrie est un argument de soutenance : le middleware est agnostique de ses
dépendances externes. Elle a aussi une valeur opérationnelle immédiate — l'implémentation
à base de règles sert de mode dégradé quand l'API d'IA est injoignable.

### 2.3 L'IA passe par l'API Gemini de Google

Choix explicite de l'utilisateur. Le modèle exact et son coût seront vérifiés au moment de
la spec de F3 plutôt que cités de mémoire.

### 2.4 Découpage couche par couche

Approche retenue : chaque feature traite un étage complet du pipeline et est livrée
terminée avant que la suivante commence. L'alternative — une tranche verticale minimale
traversant tout le pipeline dès la première feature — a été présentée et écartée par
l'utilisateur.

Le risque assumé de ce choix est explicité en section 7.

---

## 3. Découpage en features

Sept features. Chacune correspond à une session de travail et à une branche Git.

### F1 — Socle et multi-tenant · ~2 semaines

**Objectif.** Poser le modèle de données complet du projet et la notion de client.

**Contenu.** Entités JPA, repositories, migrations Flyway `V2` et suivantes. Tables
attendues :

| Table | Rôle |
|---|---|
| `client` | Le tenant : nom, identifiant public, secret HMAC, fournisseur ERP cible, configuration de scoring, actif |
| `sales_rep` | Commercial rattaché à un client, avec sa référence dans l'ERP, son secteur et sa zone |
| `raw_lead_event` | Existe déjà (`V1`) — à faire évoluer pour porter `client_id` |
| `lead` | Lead qualifié : identité, message, intention détectée, score, statut, commercial assigné |
| `crm_sync_attempt` | Trace de synchronisation : `provider_id`, statut, références renvoyées par l'ERP, erreur éventuelle |

La configuration de scoring propre à chaque client est stockée en JSONB sur `client`
plutôt que dans un modèle relationnel dédié : tant qu'aucun besoin de requêtage sur ces
règles n'existe, une table de règles serait de la complexité non gagnée.

**Critères de recette.** Les migrations s'appliquent sur une base vierge. Les repositories
sont couverts par des tests d'intégration Testcontainers. `ddl-auto: validate` passe, ce
qui prouve que les entités et le schéma Flyway concordent.

**Dépend de.** Rien.

---

### F5 — Connecteurs ERP · ~3 semaines

Placée en deuxième position malgré son numéro : voir section 4.

**Objectif.** Implémenter réellement les deux adaptateurs derrière `CrmConnector`.

**Contenu.** `DolibarrConnector` (API REST, authentification par clé `DOLAPIKEY`) et
`OdooConnector` (JSON-RPC, authentification en deux temps `common.authenticate` puis
`object.execute_kw`). Chacun traduit le modèle pivot vers le vocabulaire de son ERP —
Dolibarr sépare Tiers et Contact en deux endpoints, Odoo place les deux dans `res.partner`
distingués par `is_company` et l'opportunité dans `crm.lead`.

Stratégie d'idempotence : un message peut être rejoué après un échec partiel. Avant toute
création, l'adaptateur consulte `crm_sync_attempt` pour les références déjà obtenues et ne
recrée pas ce qui existe.

**Critères de recette.** Tests d'intégration contre un conteneur Dolibarr réel
(`docker compose --profile dolibarr`) et un conteneur Odoo (`--profile odoo`). Un rejeu
complet d'un lead déjà synchronisé ne crée aucun doublon dans l'ERP.

**Dépend de.** F1 pour `crm_sync_attempt`.

---

### F2 — Capture sécurisée · ~2 semaines

**Objectif.** L'entrée du pipeline, avec sa garantie de non-perte.

**Contenu.** Endpoint webhook identifiant le client émetteur, vérification de la signature
HMAC-SHA256 avec comparaison à temps constant et fenêtre anti-rejeu sur l'horodatage,
persistance de l'événement brut, publication sur RabbitMQ, réponse `202 Accepted`.

Point de conception à trancher dans la spec de F2 : la publication sur le broker ne doit
pas partir avant que la transaction de persistance soit validée, sous peine de publier un
message référençant une ligne inexistante. Les deux options sont un
`@TransactionalEventListener(AFTER_COMMIT)` ou un pattern outbox.

**Invariant à ne pas casser.** Le webhook ne fait aucun travail métier. Il authentifie,
écrit, publie. Tout ce qui peut être lent ou échouer vit derrière la file.

**Critères de recette.** Une signature invalide, absente ou périmée est rejetée. Une
signature valide produit une ligne en base et un message dans la file. Tests unitaires sur
le vérificateur HMAC, test d'intégration webhook vers file.

**Dépend de.** F1 pour `client` et son secret.

---

### F3 — Qualification et scoring · ~3 semaines

**Objectif.** Transformer un événement brut en lead qualifié.

**Contenu.** Consommateur de la file. Normalisation et validation des emails et des
numéros de téléphone. Déduplication sur la paire client et email dans une fenêtre
temporelle. Interface `IntentAnalyzer` avec deux implémentations : une à base de règles et
de lexique, et `GeminiIntentAnalyzer`. Moteur de scoring lisant la configuration du client.

L'implémentation à base de règles est écrite en premier et reste le mode dégradé.

**Décisions reportées à la spec de F3.** Le modèle Gemini retenu et son coût réel. Le
détail des critères de scoring et de leur pondération.

**Critères de recette.** Un doublon n'est pas traité deux fois. Un message sans texte libre
ne fait pas échouer la qualification. Une panne de l'API Gemini bascule sur les règles sans
perdre le lead.

**Dépend de.** F1 pour `lead`, F2 pour la file alimentée.

---

### F4 — Routage et attribution · ~2 semaines

**Objectif.** Choisir le commercial et le prévenir.

**Contenu.** Interface `AssignmentStrategy` avec trois implémentations : round-robin,
géographique, sectorielle. Le choix de la stratégie est une propriété du client. Création
de la tâche de rappel dans l'agenda du commercial et alerte pour les leads chauds.

**Critères de recette.** Le round-robin répartit équitablement sur un jeu de commerciaux
actifs. Un commercial inactif n'est jamais sélectionné. L'absence de commercial éligible
produit une erreur explicite plutôt qu'une attribution silencieuse à personne.

**Dépend de.** F1 pour `sales_rep`, F3 pour le lead qualifié, F5 pour écrire dans l'ERP.

---

### F6 — Monitoring et dashboard · ~3 semaines

**Objectif.** Rendre le pipeline observable et pilotable par l'agence.

**Contenu.** API REST de monitoring. Authentification du dashboard. Écrans Angular : flux
des leads, historique, état de la file et de la DLQ avec rejeu, connecteurs ERP et leur
état. Flux temps réel.

**Décisions reportées à la spec de F6.** Le mécanisme d'authentification. Le choix entre
SSE et WebSocket — SSE suffit à un flux purement descendant, mais cela se décide devant les
écrans.

**Critères de recette.** Un lead en échec apparaît dans la DLQ et peut être rejoué depuis
l'interface. Les compteurs de conversion sont cohérents avec le contenu de la base.

**Dépend de.** Toutes les features précédentes.

---

### F7 — Durcissement et livraison · ~1 à 2 semaines

**Objectif.** Rendre le projet présentable et défendable.

**Contenu.** Observabilité, intégration continue, déploiement, documentation technique et
mémoire de soutenance.

**Cible de déploiement.** Reportée à la spec de F7.

**Feature compressible.** Si le calendrier dérape, c'est ici qu'on rogne — jamais sur F1.

---

## 4. Ordre d'exécution

```
F1 → F5 → F2 → F3 → F4 → F6 → F7
```

F5 passe délibérément en deuxième position. C'est la seule couche dont le comportement
dépend d'API externes non maîtrisées. La traiter tôt laisse deux mois pour réagir si
Dolibarr ou Odoo ne se comportent pas comme leur documentation l'annonce, alors que dans
l'ordre naturel du pipeline cette découverte tomberait à un mois de la soutenance. F5 est
parfaitement testable de façon isolée contre des conteneurs ERP réels, sans que le reste du
pipeline existe.

**Charge totale estimée : 16 à 17 semaines pleines**, pour 3 à 4 mois annoncés. C'est
tendu. F7 est la variable d'ajustement.

---

## 5. Normes de développement

### 5.1 Une feature, une session, une branche

Chaque feature est traitée dans une session de travail dédiée, sur une branche
`feature/f<N>-<nom>` mergée dans `main` une fois terminée. Pas de branche d'intégration
intermédiaire : en solo, une branche `dev` sert à stabiliser le travail de plusieurs
développeurs avant publication, ce qui n'est pas le cas ici.

**Conséquence déterminante :** chaque session démarre à froid, sans le fil de la
conversation précédente. Tout ce qui n'est pas écrit dans le dépôt est perdu. Une feature
n'est donc terminée que lorsqu'une session suivante peut reprendre le travail sans qu'on
lui réexplique le contexte.

### 5.2 Trois artefacts par feature

1. **Une spec** dans `docs/superpowers/specs/`, écrite et validée avant la première ligne
   de code : périmètre, modèle de données touché, interfaces publiques, cas d'erreur,
   critères de recette.
2. **Le code et ses tests**, écrits en commençant par les tests.
3. **La mise à jour du `CLAUDE.md`**, qui est la mémoire réelle du projet entre sessions.

### 5.3 Définition du « terminé »

À vérifier avant tout merge :

- `./mvnw verify` passe, daemon Docker démarré (les tests utilisent Testcontainers).
- `npm run build` et les tests frontend passent, si le frontend est touché.
- Les migrations Flyway s'appliquent sur une base vierge (`docker compose down -v` puis
  remontée).
- Le `CLAUDE.md` reflète ce qui vient d'être construit.

### 5.4 Commits

Convention `feat:`, `fix:`, `test:`, `docs:`, `refactor:`. L'historique Git fera partie du
dossier de PFE.

---

## 6. Invariants d'architecture

Ces règles traversent toutes les features et sont les plus faciles à casser par
inadvertance.

1. **Le modèle pivot `crm/model` ne contient aucun terme propre à un fournisseur** — ni
   « thirdparty » côté Dolibarr, ni « res.partner » côté Odoo. Toute la traduction se fait
   dans l'adaptateur. Dès qu'un champ spécifique remonte dans `CrmLead`, la généralisation
   est perdue.
2. **Le webhook ne fait aucun travail métier.** Ajouter du traitement synchrone dans la
   couche `capture` casse la garantie de non-perte sous charge.
3. **Hibernate ne crée jamais de table.** Toute évolution de schéma passe par une nouvelle
   migration Flyway. Modifier une migration déjà appliquée fait échouer le démarrage.
4. **Ajouter un ERP ne modifie aucun code existant** : un sous-package, une classe
   `@Component` implémentant `CrmConnector`, une entrée de configuration. Aucun `switch`
   sur le nom du fournisseur ailleurs que dans le registre.

---

## 7. Risques

**Rien ne fonctionne bout en bout avant F6.** C'est le risque structurel de l'approche
couche par couche, accepté en connaissance de cause. Le passage de F5 en deuxième position
le réduit sans le supprimer. Point de vigilance : si le calendrier dérape, il faut arbitrer
tôt, pas en semaine 14.

**Le dimensionnement de F1 conditionne F3 et F4.** Un modèle de données bâclé fait rouvrir
les migrations dans deux features ultérieures. C'est la feature où le temps investi
rapporte le plus, et elle ne doit pas être expédiée sous prétexte qu'elle est peu
spectaculaire.

**La conformité des API ERP à leur documentation n'est pas acquise.** C'est précisément la
raison de l'ordre retenu.

**Le calendrier est tendu** : 16 à 17 semaines estimées pour 3 à 4 mois. F7 absorbe le
dérapage.

**La dépendance à une API d'IA externe** est atténuée par l'analyseur à base de règles, qui
sert de mode dégradé.

---

## 8. Prochaine étape

Écrire la spec détaillée de **F1 — Socle et multi-tenant**, dans une session dédiée, sur la
branche `feature/f1-socle-multi-tenant`.
