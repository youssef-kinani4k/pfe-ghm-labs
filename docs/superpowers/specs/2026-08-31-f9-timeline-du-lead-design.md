# F9 — La timeline dérivée du lead

Écrit le 31 août 2026, après la recette de F8. F9 est la première des cinq features
restantes de la feuille de route `F9 → F10 → F12 → F13 → F14`.

## Le problème

Le dashboard sait dire **où en est** un lead — son statut, son score, son commercial, ses
tentatives de synchronisation. Il ne sait pas dire **comment il y est arrivé**.

L'écran `leads/:id` porte pourtant déjà la matière : `LeadDetail` assemble l'événement de
capture, la ligne de qualification et l'historique ERP. Mais il les présente en blocs
juxtaposés, chacun avec ses propres dates, et c'est au lecteur de reconstituer la
chronologie dans sa tête. La question « pourquoi ce lead n'est-il pas dans Dolibarr ? » se
répond aujourd'hui en croisant trois zones de l'écran.

F9 **dérive** de l'existant une seule lecture ordonnée dans le temps. Elle ne collecte
qu'une donnée nouvelle, et à contrecœur : la date d'attribution, qui n'existe nulle part —
voir la section suivante.

## Le trou dans les horodatages, et ce qu'on en fait

L'inventaire des dates disponibles a révélé une absence qui décide de tout le reste :

| Étape | Horodatage |
| --- | --- |
| Capture | `raw_lead_event.received_at` |
| Qualification | `lead.created_at` |
| **Attribution (routage)** | **aucun** |
| Synchronisation ERP | `crm_sync_attempt.attempted_at`, une par tentative |
| Mort et rejeu | `dead_letter.dead_at`, `dead_letter.replayed_at` |

`lead.updated_at` ne comble pas ce trou. Il bouge à chaque écriture : il vaut la date
d'attribution pour un lead resté `ROUTED`, mais la date de synchronisation pour un lead
`SYNCED`. S'en servir donnerait une timeline datée pour les leads en échec et muette pour
ceux qui ont réussi — l'inverse de l'intuition.

**Décision : F9 porte une migration `V7` qui ajoute `lead.routed_at`.** La feuille de route
annonçait F9 « sans migration » ; cette prévision tombe. Une chronologie dont l'étape
centrale n'est pas datable n'est pas une chronologie.

**Conséquence à propager : F10 prendra `V8`**, et son index unique sur `dead_letter` avec.
La feuille de route annonce encore `V7` pour F10 — elle avait déjà été corrigée une fois,
`V6` ayant été consommée par F11.2.

## L'écriture de `routed_at`

```sql
-- V7__lead_routed_at.sql
ALTER TABLE lead ADD COLUMN routed_at timestamptz;
```

**Nullable, et sans remplissage rétroactif.** Remplir l'historique depuis `updated_at`
inventerait une date pour tout lead déjà synchronisé. Une case vide est une information ;
une date fausse n'en est pas une. La timeline affiche « date inconnue » pour les leads
attribués avant cette migration, et cet affichage n'est pas un défaut à corriger plus tard :
c'est la seule chose vraie qu'on puisse en dire.

La colonne se pose dans `RoutedLeadWriter`, à côté du `setStatus(LeadStatus.ROUTED)`
existant — **le seul endroit du code qui pose ce statut**, vérifié. Elle est écrite dans la
même transaction que le statut et l'assignation : les trois sont un seul fait, et un
`routed_at` sans commercial serait un état incohérent.

## Le contrat

**`GET /api/leads/{id}/timeline`** rend une liste ordonnée, **sans pagination**. Le volume
est borné par construction : une capture, une qualification, une attribution, au plus trois
tentatives ERP avant la DLQ, une mort et un rejeu. `404` sur un lead inconnu, comme
`LeadDetail`.

```java
public record TimelineEntry(
        TimelineEventType type,      // CAPTURE, QUALIFICATION, ATTRIBUTION, SYNC_ERP, MORT, REJEU
        Instant at,                  // null pour une attribution anterieure a V7
        TimelineOutcome outcome,     // SUCCES, ECHEC, NEUTRE
        Map<String, String> details) // source, score, intention, commercial, erreur...
```

Un record plat et typé plutôt qu'une hiérarchie scellée : les six types partagent la même
forme, et une hiérarchie coûterait une désérialisation polymorphe côté Angular pour aucun
gain.

**Aucune phrase n'est composée côté serveur.** Le backend rend des faits typés, le template
Angular les met en français. C'est délibérément la règle inverse de `replayWarning`, qui
reste calculé côté serveur parce qu'il encode une **décision** du backend — la
non-idempotence du tour de rôle de F4 — et non un libellé.

### Pourquoi un endpoint dédié plutôt qu'un champ de `LeadDetail`

`LeadDetail` porte déjà `rawEvent` et `syncAttempts`, c'est-à-dire la matière brute dont la
timeline est la forme dérivée. L'y ajouter ferait voyager deux fois la même chose.

La raison décisive est cependant tournée vers la suite : **F10 devra rafraîchir la seule
chronologie après une réattribution**, sans refaire tout le détail. Un endpoint séparé rend
ce geste trivial. Le prix est un appel HTTP de plus à l'ouverture de l'écran, ce qui est
sans conséquence sur un dashboard d'agence.

`LeadDetail` n'est donc pas modifié, et l'écran de détail actuel n'est pas touché.

## La dérivation

Un `LeadTimelineService` neuf, `@Transactional(readOnly = true)`, lit quatre dépôts qui
existent tous : `LeadQueryRepository`, `RawLeadEventRepository`, `CrmSyncAttemptRepository`,
`DeadLetterRepository`. **Il n'écrit rien** — l'invariant de `monitoring/`, qui n'écrit que
`dead_letter`, tient sans exception.

Un `LeadTimelineController` à part plutôt qu'une méthode de plus sur `LeadQueryController` :
une classe, une raison de changer.

### La règle d'ordre

C'est le seul point subtil du service.

1. Les entrées **datées** se trient chronologiquement.
2. L'attribution **sans date** se place **juste après la qualification**, à sa position
   connue dans le pipeline.

Un tri qui rejetterait les `null` en tête ou en queue — le comportement par défaut de la
plupart des comparateurs — mentirait sur la chronologie de tout l'historique antérieur à
`V7`. L'ordre des étapes est connu même quand leur date ne l'est pas.

## L'écran

Un composant `lead-timeline` **local à `features/leads/lead-detail/`**, pas dans `shared/` :
rien d'autre ne le consomme. Il est alimenté par une méthode de service appelant le nouvel
endpoint, et rend ses entrées depuis un signal — F10 rouvrira ce composant pour y accrocher
la réattribution, donc il naît en sachant que sa liste bougera.

**Le visuel passe par le plugin `ui-ux-pro-max`, invoqué avant d'écrire le composant** et
non en relecture, comme le veut la règle permanente du projet. Une chronologie a des choix
propres à prendre : la marque des échecs, la lisibilité en clair et en sombre, et surtout le
traitement de la case « date inconnue », qui doit se lire comme une absence assumée et non
comme une erreur d'affichage.

## Les tests

| Test | Ce qu'il verrouille |
| --- | --- |
| Contrat JSON de l'endpoint | Le corps rendu, pas le DTO — comme le reste de `monitoring/` |
| Lead complet | Les six types d'entrée présents, et l'ordre chronologique |
| Lead sans `routed_at` | L'attribution non datée se place après la qualification, pas en tête |
| Lead jamais synchronisé | Aucune entrée `SYNC_ERP`, aucun échec |
| Lead inconnu | `404` |
| `RoutedLeadWriter` | `routed_at` posé dans la même transaction que le statut et l'assignation |
| Karma sur le composant | Le rendu de la liste, et le cas « date inconnue » |

Les tests de persistance passent par `@SpringBootTest` et jamais `@DataJpaTest` : les
`AttributeConverter` sont des `@Component`, et la tranche JPA ne les inclut pas.

## Ce que F9 ne fait pas

À dire explicitement, pour que la session suivante ne le cherche pas dans le code :

- **Aucune réattribution manuelle** — c'est F10, et c'est précisément pour lui donner un
  endroit où s'afficher que F9 vient avant.
- **Aucune notification** au commercial. Reste une limite assumée, à défendre à l'oral.
- **Aucun recalcul rétroactif** de score.
- **Aucun remplissage de `routed_at`** pour les leads déjà attribués.
- **Aucun graphique.** La timeline est une liste ordonnée, pas une frise proportionnelle au
  temps écoulé.

## La dette payée au passage

La feuille de règlement des dettes plaçait en F9 la correction du **secret HMAC de
démonstration**, faux dans `R__demo_data.sql` depuis F7.2, au motif que F9 serait la première
session à remonter une base de démonstration.

Cette dette a été **partiellement payée avant l'ouverture de F9**, dans le commit `ee8f397` :
le mensonge est confirmé par un `401` sur base neuve et il est désormais signalé dans le
fichier, dans `docs/webhook-integration.md` et dans `CLAUDE.md`. **Ce qui reste dû** est la
correction elle-même : produire une valeur chiffrée avec la clé maître de développement qui
corresponde au clair documenté. Le geste de contournement — faire tourner le secret depuis
l'écran « Boutiques » — fonctionne et est documenté.
