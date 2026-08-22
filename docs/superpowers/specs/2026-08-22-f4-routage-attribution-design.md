# F4 — Routage et attribution

Statut : validé en session de conception, prêt pour le plan d'implémentation.
Dépend de : F1 (livrée) pour `sales_rep`, `client.assignment_strategy` et
`lead.assigned_sales_rep_id` ; F3 (livrée) pour le lead qualifié et la file
`leadflow.leads.qualified` ; F5 (livrée) pour `CrmSyncService`.
Position dans le planning général : cinquième feature exécutée, après F1, F5, F2 et F3.

---

## 1. Objectif

Choisir le commercial destinataire du lead, puis pousser le lead dans l'ERP du client.

À la fin de F4, un message `lead.qualified` produit un lead portant un
`assigned_sales_rep_id` et le statut `ROUTED`, puis une synchronisation ERP qui le fait
passer à `SYNCED` en laissant une ligne `crm_sync_attempt`. Un client sans commercial actif
produit une erreur explicite qui finit en DLQ plutôt qu'une attribution silencieuse à
personne.

F4 ferme la dette la plus visible du projet : `CrmSyncService` existe depuis F5 et n'est
appelé par personne en dehors des tests. **Après F4, le pipeline est complet de bout en
bout** — du formulaire client à la fiche dans l'ERP.

Ce que F4 ne fait pas : créer la tâche de rappel dans l'agenda du commercial, envoyer une
alerte pour les leads chauds, exposer quoi que ce soit en REST (F6).

---

## 2. Contexte et contradictions levées

**Le plan général donne à F4 deux contenus, et ils n'ont pas le même coût.** L'attribution
est un calcul sur des données déjà présentes. La tâche d'agenda, elle, demanderait une
opération nouvelle sur le port `CrmConnector` — donc une implémentation Dolibarr, une
implémentation Odoo, et une **quatrième étape à loger dans `CrmSyncState`**, qui n'en
modélise que trois. C'est précisément la limitation documentée dans le Javadoc de
`DolibarrConnector` depuis F5. Ces deux volets sont séparés : F4 fait l'attribution, la
notification attend.

**Le round-robin n'est pas idempotent.** Rejouer une attribution décale la rotation : le
commercial servi une seconde fois pour le même lead ne l'est pas pour le suivant. Or la
livraison est at-least-once et le rejeu depuis la DLQ est le mode de reprise du projet.
D'où la séparation en deux étapes : un échec de l'ERP ne doit pas renvoyer l'attribution
au consommateur.

**`CrmSyncService.synchronise(leadId)` attend un lead déjà attribué.** Il lit
`lead.assigned_sales_rep_id`, résout la référence du commercial dans l'ERP et la mémorise
dans `sales_rep.crm_ref`. F4 est donc obligatoirement en amont de lui — l'ordre n'est pas
un choix de confort.

**Le schéma est complet.** `sales_rep` porte `sector`, `zone`, `active` et `crm_ref` ;
`client` porte `assignment_strategy` ; `lead` porte `assigned_sales_rep_id` et le statut
`ROUTED`, jamais posé jusqu'ici. F4 n'ajoute aucune migration.

---

## 3. Décisions

### 3.1 Deux étapes, deux files

Un consommateur de `leadflow.leads.qualified` attribue et publie sur
`leadflow.leads.routed` ; un second consommateur appelle `CrmSyncService`.

```
leadflow.leads.qualified ──> [routing] ──> leadflow.leads.routed ──> [crm] ──> ERP
                              attribue                                synchronise
                              ROUTED                                  SYNCED
```

La raison est le comportement en panne. Un ERP injoignable est le cas le plus fréquent et
le moins évitable ; avec un seul consommateur, il renverrait en DLQ un message dont le
rejeu réattribuerait le lead. Avec deux, la DLQ ne contient que ce qui a réellement échoué,
et le rejeu s'appuie sur `CrmSyncState`, conçu pour cela depuis F5.

Le prix assumé : un aller-retour de plus par lead, et une file supplémentaire à surveiller
dans l'écran de F6.

### 3.2 Le port `AssignmentStrategy` et son registre

```java
public interface AssignmentStrategy {
    AssignmentStrategyType type();
    Optional<SalesRep> choisit(Lead lead, List<SalesRep> eligibles);
}
```

Trois `@Component` — `RoundRobinStrategy`, `GeographicStrategy`, `SectorStrategy` — et un
`AssignmentStrategyRegistry` qui les collecte par injection de `List<AssignmentStrategy>` et
les indexe par `type()`. C'est le motif de `CrmConnectorRegistry` : **aucun `switch` sur la
stratégie nulle part**, et une quatrième stratégie s'ajoute en écrivant une classe.

Le registre échoue au démarrage si deux stratégies déclarent le même `type()`, ou si une
valeur de l'énumération n'a aucune implémentation : une erreur de câblage doit se voir au
démarrage, pas au premier lead d'un client mal configuré.

`choisit` reçoit la liste des éligibles plutôt que d'aller la chercher : les stratégies
restent des fonctions pures, testables sans base ni Spring.

### 3.3 Le round-robin est le socle des trois stratégies

`GeographicStrategy` filtre les commerciaux dont la `zone` correspond au
`lead.country_code` ; `SectorStrategy` filtre sur `sector` contre `lead.sector`. Puis
**les deux délèguent au même choix** : parmi les commerciaux retenus, celui dont
l'attribution la plus récente est la plus ancienne.

Sans cette délégation, deux commerciaux du même secteur ne seraient jamais départagés
équitablement — le premier de la liste prendrait tout. La comparaison est exacte et
insensible à la casse, comme le ciblage sectoriel du barème de scoring de F3.

### 3.4 Le tour se lit dans la table `lead`, pas dans un compteur

Le prochain commercial est celui dont `max(lead.created_at)` est le plus ancien ; ceux qui
n'ont jamais rien reçu passent d'abord.

Un curseur persisté serait un round-robin plus strict, mais il demanderait une migration
alors que le schéma est déclaré complet, un verrou pour deux livraisons concurrentes, et il
pourrait pointer vers un commercial désactivé entre-temps. L'état déduit, lui, ne peut pas
diverger de la réalité : il **est** la réalité. Un commercial ajouté aujourd'hui n'a aucune
attribution, donc il passe en tête — ce qui est le comportement voulu.

Le prix assumé : une requête d'agrégation par lead, sur un index existant
(`idx_lead_client_email_created` ne la sert pas, mais la volumétrie par client reste celle
d'une PME — quelques milliers de lignes). Si cela devient un point chaud, la réponse est un
index sur `(client_id, assigned_sales_rep_id, created_at)`, pas un compteur.

### 3.5 Repli sur le round-robin quand le filtre ne rend personne

Un lead du secteur « textile » chez un client qui n'a aucun commercial « textile » part au
commercial actif servi le plus anciennement, et le repli est logué en `INFO` avec le
critère qui n'a pas trouvé preneur.

L'alternative — échouer — laisserait des leads en souffrance à cause d'une configuration
client incomplète, alors qu'un prospect qui attend coûte plus cher qu'une attribution
imparfaite. Le log existe pour que l'agence voie la configuration à corriger.

### 3.6 Aucun commercial actif : exception, puis DLQ

Un client dont **tous** les commerciaux sont inactifs — ou qui n'en a aucun — fait lever
une `AssignmentException` par le service. Le listener ne la rattrape pas : trois tentatives,
puis DLQ.

C'est le seul échec de F4 qui mérite la DLQ, et il la mérite parce qu'**un humain peut le
réparer** : activer un commercial, puis rejouer le message. Cela le distingue de l'événement
sans email exploitable de F3, qui échouerait éternellement à l'identique et reçoit pour cela
un statut terminal. CLAUDE.md fait de la DLQ la source de vérité des leads en échec ; c'est
là que l'écran « File d'attente » de F6 ira les chercher.

### 3.7 Un lead déjà routé n'est pas réattribué, mais son message repart

`LeadRoutingService` commence par relire le lead. S'il porte déjà un
`assigned_sales_rep_id`, l'attribution est sautée — c'est ce qui protège la rotation d'un
rejeu — **mais `lead.routed` est republié quand même**.

Republier est sans danger : `CrmSyncService` reconstruit l'état antérieur depuis
`crm_sync_attempt` et saute toute étape dont la référence est connue. Ne pas republier
serait dangereux : un message perdu entre les deux étapes laisserait un lead `ROUTED` que
plus rien ne synchroniserait.

### 3.8 Le contrat de file `RoutedLeadMessage`

```java
public record RoutedLeadMessage(
        UUID leadId,
        UUID clientId,
        UUID salesRepId,
        Instant routedAt) {
}
```

Une référence, pas un contenu — même raison qu'en F2 et F3. `salesRepId` voyage parce que
c'est la décision que cette étape vient de prendre, et qu'un consommateur qui la reçoit n'a
pas à relire la ligne pour savoir ce qui a été décidé.

`com.leadflow.routing` doit rejoindre `RabbitMQConfig.PAQUETS_DE_CONFIANCE`, sans quoi le
second consommateur refusera de désérialiser le message. La correspondance est exacte : ni
préfixe, ni joker.

### 3.9 Les deux listeners sont des beans conditionnels

`leadflow.routing.listener.enabled` et `leadflow.crm.listener.enabled`, vrais par défaut,
faux dans `src/test/resources/application.properties`.

Ce n'est pas une précaution de principe : F3 a montré qu'un consommateur actif vole aux
tests de la couche amont les messages qu'ils viennent de publier, et que
`spring.rabbitmq.listener.simple.auto-startup=false` **ne suffit pas** — le cache de
contextes de test met un contexte en pause puis le redémarre, et `start()` réveille les
beans `Lifecycle` en ignorant `auto-startup`. Un bean absent, lui, ne peut pas redémarrer.
Les classes qui veulent le consommateur le rallument par `@TestPropertySource`.

### 3.10 `SYNCED` est posé par l'étape CRM, pas par le connecteur

Le consommateur de `leadflow.leads.routed` appelle `CrmSyncService.synchronise(leadId)` puis
fait passer le lead à `SYNCED`. `CrmSyncService` n'est pas modifié : il ne connaît pas le
cycle de vie du lead, il synchronise et trace. Un échec laisse le lead `ROUTED` et une ligne
`crm_sync_attempt` en `FAILED` — l'état exact dont le rejeu a besoin.

---

## 4. Organisation du code

```
routing/
├── package-info.java              enrichi : invariants de l'etape
├── LeadRoutingListener.java       @RabbitListener sur leadflow.leads.qualified
├── LeadRoutingService.java        orchestration : idempotence, strategie, ecriture, publication
├── AssignmentStrategy.java        port
├── AssignmentStrategyRegistry.java resolution par AssignmentStrategyType
├── RoundRobinStrategy.java        le moins recemment servi
├── GeographicStrategy.java        filtre sur zone, puis round-robin
├── SectorStrategy.java            filtre sur secteur, puis round-robin
├── AssignmentException.java       aucun commercial eligible
├── RoutedLeadWriter.java          ecriture ROUTED en transaction propre
├── RoutedLeadMessage.java         contrat de file
└── RoutedLeadPublisher.java       publication sur lead.routed

crm/
└── CrmSyncListener.java           @RabbitListener sur leadflow.leads.routed, passe a SYNCED
```

### Fichiers touchés hors du package

| Fichier | Modification |
| --- | --- |
| `config/RabbitMQConfig.java` | `ROUTED_QUEUE`, `ROUTED_ROUTING_KEY`, la file, son binding, `com.leadflow.routing` dans la liste blanche |
| `qualification/LeadRepository.java` | requête d'agrégation « dernière attribution par commercial » |
| `qualification/LeadStatus.java` | rien à ajouter — `ROUTED` et `SYNCED` existent |
| `application.yml` | les deux commutateurs de listener |
| `src/test/resources/application.properties` | les deux commutateurs à `false` |
| `CLAUDE.md` | section « Routage », état actuel |

`RoutedLeadWriter` existe pour la même raison que `LeadWriter` en F3 : l'orchestrateur n'est
pas transactionnel, et l'écriture doit être commitée avant que le message ne parte, sans
quoi le consommateur suivant lirait une ligne qui n'existe pas encore.

---

## 5. Configuration

```yaml
leadflow:
  routing:
    listener:
      enabled: true
  crm:
    listener:
      enabled: true
```

Rien d'autre. La stratégie est une donnée du client, pas un réglage d'instance — c'est
`client.assignment_strategy`, posé par F1.

---

## 6. Migration

**Aucune.** `sales_rep.sector`, `sales_rep.zone`, `sales_rep.active`,
`client.assignment_strategy`, `lead.assigned_sales_rep_id` et les statuts `ROUTED` /
`SYNCED` viennent tous de `V2`. La colonne `lead.status` est un `VARCHAR(32)` sans
contrainte `CHECK` : les deux valeurs nouvelles à l'usage ne demandent rien.

---

## 7. Gestion d'erreurs

| Cas | Réponse | Justification |
| --- | --- | --- |
| Aucun commercial actif | `AssignmentException` → 3 tentatives → DLQ | Réparable par un humain, donc le rejeu a un sens |
| Filtre géographique ou sectoriel vide | Repli round-robin, log `INFO` | Un prospect qui attend coûte plus cher qu'une attribution imparfaite |
| Lead introuvable | Acquitté, log `WARN` | Échec déterministe : la DLQ n'y peut rien |
| Lead déjà attribué | Attribution sautée, message republié | Protège la rotation sans casser la chaîne |
| Publication de `lead.routed` en échec | Log `WARN`, pas de relance | Le lead est écrit ; faire échouer le consommateur renverrait en DLQ un message déjà traité |
| ERP injoignable | `CrmSyncException` → DLQ | La ligne `crm_sync_attempt` est déjà écrite par `CrmSyncService` |

---

## 8. Tests

### Étage 1 — unitaire, sans Spring ni base

Les trois stratégies sont des fonctions pures sur une liste de commerciaux :

- le round-robin retient le commercial dont la dernière attribution est la plus ancienne ;
- un commercial sans aucune attribution passe avant tous les autres ;
- une liste vide rend `Optional.empty()` — c'est l'orchestrateur qui lève, pas la stratégie ;
- la stratégie géographique retient le bon pays, la sectorielle le bon secteur, casse
  ignorée ;
- filtre vide : les deux retombent sur l'ensemble des éligibles ;
- le registre rend la bonne implémentation pour chaque valeur de l'énumération, et refuse
  de démarrer si l'une d'elles n'a pas de titulaire.

### Étage 2 — intégration, `@SpringBootTest` + Testcontainers

- un lead `QUALIFIED` en base ressort `ROUTED` avec un `assigned_sales_rep_id` non nul, et
  un `RoutedLeadMessage` cohérent est présent sur `leadflow.leads.routed` ;
- équité observable : trois leads pour deux commerciaux se répartissent 2/1, pas 3/0 ;
- un commercial désactivé n'est jamais retenu, même s'il est le moins récemment servi ;
- un client sans commercial actif fait lever `AssignmentException` ;
- rejeu : qualifier deux fois le même lead ne change pas le commercial attribué, et publie
  quand même le message ;
- bout en bout, listeners rallumés par propriété et faux connecteur ERP : un message
  `lead.qualified` produit un lead `SYNCED` et une ligne `crm_sync_attempt` en `SUCCESS`.

Le faux connecteur est un `@Component` de test déclarant son propre `providerId`, comme le
`ConnecteurEspion` de `CrmSyncServiceTest` : aucun test de F4 ne doit dépendre d'un vrai
Dolibarr.

---

## 9. Critères de recette

1. Le round-robin répartit équitablement sur un jeu de commerciaux actifs.
2. Un commercial inactif n'est jamais sélectionné.
3. L'absence de commercial éligible produit une erreur explicite, visible en DLQ, et non une
   attribution silencieuse à personne.
4. Un lead qualifié traverse tout le pipeline sans intervention : `QUALIFIED` → `ROUTED` →
   `SYNCED`, avec sa trace `crm_sync_attempt`.
5. La stratégie appliquée est bien celle du client, vérifiable en changeant
   `client.assignment_strategy` sans redéployer.

---

## 10. Hors périmètre

- **Tâche de rappel dans l'agenda du commercial** : demande une opération nouvelle sur le
  port `CrmConnector` et une quatrième étape dans `CrmSyncState`.
- **Alerte e-mail et notification temps réel des leads chauds** : le `seuilChaud` du barème
  de F3 reste inutilisé jusqu'à F6, qui portera le flux temps réel.
- **API REST de routage** (réattribuer un lead à la main, rejouer depuis la DLQ) : F6.
- **Filet de republication pour `lead.qualified` et pour `lead.routed`**. Le Javadoc de
  `QualifiedLeadPublisher` désignait F4 comme la feature qui rendrait le premier écrivable,
  et c'est vrai : à partir d'ici un lead quitte l'état `QUALIFIED`, donc un balayage
  `findByStatusAndCreatedAtBefore(QUALIFIED, seuil)` est borné au lieu de republier la table
  entière en boucle. Le même raisonnement vaut pour `lead.routed`, borné par `SYNCED`. Les
  deux filets sont néanmoins **reportés à F6**, où ils rejoignent le rejeu manuel depuis la
  DLQ : ce sont deux faces du même écran, et les écrire ici sans interface pour les observer
  reviendrait à ajouter un mécanisme silencieux de plus. La dette est donc reconduite en
  connaissance de cause, pas oubliée.

---

## 11. Risques et dettes assumées

### 11.1 L'équité est approximative sous concurrence

Deux livraisons traitées en parallèle lisent la même « dernière attribution » et peuvent
choisir le même commercial. La concurrence AMQP est celle par défaut — un consommateur —
donc le cas ne se produit pas aujourd'hui ; le jour où il se produira, la réponse est un
`SELECT ... FOR UPDATE` sur la ligne client, pas un compteur applicatif. C'est la même
hypothèse mono-instance que celle déjà portée par `PendingEventRelay`.

### 11.2 Le repli silencieux masque une configuration incomplète

Un client dont aucun commercial ne porte de `zone` verra toutes ses attributions passer par
le repli round-robin, et seul le log le dira. F6 devrait exposer ce compteur ; en attendant,
c'est une ligne de log par lead.

### 11.3 La correspondance zone/pays est un pari

`sales_rep.zone` est un texte libre (« nord », « sud ») alors que `lead.country_code` est un
code ISO à deux lettres. La stratégie géographique compare donc deux vocabulaires qui ne se
recouvrent que si l'agence a rempli `zone` avec des codes pays. C'est acceptable — le repli
couvre le cas — mais cela veut dire que la stratégie géographique est, en pratique, une
stratégie « par code pays ». Le renommage de la colonne appartiendrait à une migration, donc
à une autre feature.

---

## 12. Prochaine étape

Écrire le plan d'implémentation de F4 avec la skill `writing-plans`, puis exécuter tâche par
tâche en TDD sur la branche `feature/f4-routage-attribution`.
