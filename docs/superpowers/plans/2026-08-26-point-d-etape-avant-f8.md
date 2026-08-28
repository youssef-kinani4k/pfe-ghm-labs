# Point d'étape — 26 août 2026, avant F8

Session sans code : relecture de l'architecture backend pour se resituer avant la suite.
Rien n'a été modifié dans `backend/` ni dans `frontend/`. Ce document existe pour que l'état
survive à la conversation.

## Où en est le dépôt

Branche `main`, arbre propre, dernier commit `ecbf1b1`. Les sept branches `feature/*` sont
conservées et toutes fusionnées :

| Feature | Contenu | Branche |
| ------- | ------- | ------- |
| F1 | Socle multi-tenant, chiffrement des secrets, `V1`–`V2` | `feature/f1-socle-multi-tenant` |
| F2 | Capture sécurisée : HMAC, idempotence `V3`, filet de republication | `feature/f2-capture-securisee` |
| F3 | Qualification : normalisation, dédup, Gemini, scoring | `feature/f3-qualification-scoring` |
| F4 | Routage : trois stratégies, registre sans `switch` | `feature/f4-routage-attribution` |
| F5 | Connecteurs ERP : port `CrmConnector`, Dolibarr, Odoo | `feature/f5-connecteurs-erp` |
| F6 | Monitoring : API de lecture, journal des morts `V4`, SSE | `feature/f6-monitoring-dashboard` |
| F7 | Gestion des boutiques depuis l'interface | `feature/f7-gestion-des-boutiques` |

Le pipeline est complet de bout en bout : un lead traverse `QUALIFIED` → `ROUTED` → `SYNCED`
sans intervention. Backend : 8 packages, ~8 500 lignes, 64 classes de test, 5 files RabbitMQ,
migrations `V1` à `V4`. Frontend : huit écrans Angular.

## Produit par cette session

Un schéma visuel de l'architecture backend, publié comme artefact consultable :
<https://claude.ai/code/artifact/3d4c285f-9668-4e52-b3b3-37f5716b0146>

Il porte quatre figures — le trajet d'un lead avec ses files, le motif transactionnel commun
aux trois consommateurs, le port `CrmConnector` et sa frontière, le chemin d'échec vers
`dead_letter` — plus la grille des packages, les migrations, les cinq variables
d'environnement et sept invariants. La page est privée ; elle se partage depuis son propre
menu si elle sert en soutenance.

## Ce qui reste ouvert

Repris de la fin de F7 et de la section « Ce qui n'existe pas » de `CLAUDE.md`, du plus lourd
au plus léger.

### Chantiers de fond

1. **Aucun déploiement.** Pas d'intégration continue, pas d'image de production, et CORS
   n'autorise toujours que `http://localhost:4200`. C'est le dernier obstacle avant une mise
   en service, et donc le candidat naturel pour F8.
2. **`client.scoring_config` n'est exposé par aucune route d'administration.** Le barème
   d'une boutique — poids des critères, seuil « chaud », listes cibles — ne se règle qu'en
   base, ce qui contredit la règle « toute opération courante passe par l'interface ». Ce
   serait le contenu d'un F7.1, avec l'occasion de faire enfin servir
   `ScoringConfig.seuilChaud`, lu et porté depuis F3 sans que rien ne s'en serve.
3. **Aucune notification n'est envoyée au commercial** : ni tâche d'agenda dans l'ERP, ni
   alerte pour les leads chauds.
4. **Aucune réattribution manuelle.** Un lead attribué au mauvais commercial ne se corrige
   que par un rejeu depuis le journal des morts, ou en base.

### Points plus étroits

- **`POST /api/admin/crm/test` n'a aucune restriction de destination.** Le serveur appelle
  l'URL que l'opérateur lui donne. Acceptable sur une console interne à compte unique ; à
  restreindre — liste blanche d'hôtes, refus des adresses privées — si le dashboard s'ouvre
  aux boutiques elles-mêmes.
- **L'alias `companyname` manque à `PayloadFieldMapper`.**
- **Aucun graphique** dans le dashboard : les répartitions sont des compteurs et des barres
  de progression. Aucune bibliothèque n'est installée, et c'était un choix.
- **Le bouton de création d'une boutique n'a jamais été manipulé dans un navigateur.**
- **Le connecteur Odoo n'a pas été éprouvé en recette** : seul le profil `dolibarr` était
  monté. Sa sonde reste couverte par `OdooSondeTest`.

### Deux ajouts de documentation proposés et non tranchés

Rien n'a été écrit à ce jour pour l'un ni pour l'autre.

1. **Annexe « produire l'URL et la clé depuis l'interface Dolibarr »** dans
   `docs/boutique-onboarding.md` : activer les modules API, Tiers et Projets, créer un
   utilisateur de service plutôt qu'`admin` — la clé hérite de ses permissions —, initialiser
   la clé sur sa fiche, composer l'URL en ajoutant `/api/index.php` à la racine.
2. **Note sur le doublon de tiers.** `DolibarrConnector` ne cherche pas de tiers existant par
   email avant d'en créer un : seule l'opportunité est cherchée par sa `ref`. Deux leads du
   même prospect séparés par plus que la fenêtre de déduplication produisent donc deux tiers
   dans l'ERP, à fusionner à la main.

## Dettes techniques connues, assumées

- **Mono-instance à trois endroits** : `PendingEventRelay`, le tour de rôle de F4 et le
  registre d'émetteurs SSE de `LeadStreamBroadcaster`. Deux instances demanderaient
  respectivement un `SELECT ... FOR UPDATE SKIP LOCKED`, un verrou, et un exchange `fanout`
  avec une file exclusive par instance.
- **`dead_letter` n'a aucune contrainte d'unicité.** La livraison étant at-least-once, une
  même mort livrée deux fois écrirait deux lignes.
- **Deux limitations du pivot ERP**, documentées dans le Javadoc de `DolibarrConnector` : le
  rattachement du responsable n'a pas de logement dans `CrmSyncState`, et la `ref`
  d'opportunité est tirée au hasard.

## Reprendre

Pour se resituer : `git log --oneline -10`, la section « État actuel » de `CLAUDE.md`, puis
ce document. Le schéma ci-dessus donne la vue d'ensemble du backend en quatre figures.

La prochaine session devrait trancher entre **F8 déploiement** — le dernier obstacle réel à
une mise en service — et **F7.1 réglage du scoring depuis l'interface**, qui referme une
promesse déjà faite à l'écran.
