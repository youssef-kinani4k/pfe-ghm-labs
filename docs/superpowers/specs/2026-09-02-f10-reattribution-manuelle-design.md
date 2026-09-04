# F10 — Reattribution manuelle et journal d'actions

_Design valide le 2 septembre 2026. Une session estimee. Touche `routing/` en ecriture,
`monitoring/` en lecture, une migration `V8`, l'ecran de detail d'un lead._

## Le manque que la feature referme

Un lead attribue au mauvais commercial ne se corrige aujourd'hui que par un rejeu depuis le
journal des morts, ou en base. C'est le dernier trou d'exploitation du produit : le pipeline
sait tout faire seul, mais rien ne permet a l'operateur de corriger une decision
d'attribution.

La feature ajoute le geste, et le **journal qui le retient**. Elle absorbe au passage une
dette deja ecrite : `dead_letter` ne retient d'un rejeu que `replayed_by` et `replayed_at`,
jamais son motif ni son resultat.

## Les quatre decisions de perimetre

**Le journal porte les actions sur un lead**, et non tout geste d'ecriture du dashboard. Un
audit global de l'operateur — rotation de secret, activation d'une boutique — est un chantier
de gouvernance sans consommateur demande, et il obligerait la table a porter un type de cible
plutot qu'un `lead_id`, donc a renoncer a s'afficher dans la timeline de F9.

**La reattribution est ouverte aux leads `ROUTED` et `SYNCED`**, les deux seuls statuts ou un
commercial est deja pose. Sur un lead `SYNCED`, l'ecran avertit avant confirmation que l'ERP
conservera l'ancien responsable. Ouvrir le geste a `QUALIFIED` en ferait une attribution
manuelle en course avec le consommateur de `leadflow.leads.qualified` ; le fermer a `SYNCED`
laisserait sans reponse le cas le plus frequent, l'erreur d'attribution se voyant rarement
dans les secondes qui suivent le routage.

**Le motif est obligatoire**, en texte libre court. Un journal dont la moitie des lignes n'ont
pas de motif ne sert a rien six mois plus tard, et le geste est rare. Une liste de motifs
predefinis a ete ecartee : on inventerait aujourd'hui une taxonomie que personne n'a validee
sur le terrain, alors qu'elle se deduira plus tard des textes libres — l'inverse n'etant pas
vrai.

**Le geste vit dans la fiche du lead uniquement.** Pas d'action par ligne dans la liste, que
le motif obligatoire rendrait penible et qui n'affiche pas le contexte justifiant la
correction. Pas de reattribution en lot : redistribuer le portefeuille d'un commercial
desactive est un autre besoin, avec sa propre strategie de repartition.

## Ou vit l'ecriture

**Dans `routing/`.** Pas dans `monitoring/`, dont la surete tient a ce qu'il n'ecrit que
`dead_letter`. Pas dans `tenant/`, qui porte les donnees de reference — client et commerciaux
— et non les leads. Le choix du commercial est la responsabilite de `routing/` depuis F4, et
la reattribution est ce meme choix repris a la main.

| Classe | Role |
| --- | --- |
| `ReattributionService` | orchestre : controles, ecriture, journalisation. Non transactionnel, comme `LeadRoutingService` |
| `RoutedLeadWriter.reattribue` | methode ajoutee au writer existant, en `REQUIRES_NEW` comme sa voisine |
| `LeadActionJournal` | ecrit la ligne d'action en `REQUIRES_NEW`, sur le modele de `DeadLetterJournal` |
| `LeadAction`, `LeadActionRepository` | l'entite et son depot |
| `LeadReassignmentController` | le point d'entree HTTP |

**Aucun message n'est publie.** Ni sur `lead.routed`, ni ailleurs. Le tour de role n'est pas
idempotent — rejouer une attribution decale la rotation — et `LeadRoutingService` republierait
vers l'ERP un lead deja synchronise. La reattribution est une **ecriture directe assumee** par
l'operateur, tracee ; pas un rejeu de file.

**`reattribue` ne touche ni le statut ni `routed_at`.** Un lead `SYNCED` reste `SYNCED`. Et
`routed_at` date l'attribution automatique, un fait qui a eu lieu : la reattribution est un
fait distinct, date dans le journal. C'est l'assertion qui empeche la regression la plus
probable de cette feature.

## La migration `V8`

```sql
CREATE TABLE lead_action (
    id                    UUID         PRIMARY KEY,
    lead_id               UUID         NOT NULL REFERENCES lead(id),
    action                VARCHAR(32)  NOT NULL
        CONSTRAINT ck_lead_action_action CHECK (action IN ('REATTRIBUTION','REJEU','ECART')),
    actor                 VARCHAR(120) NOT NULL,
    reason                TEXT         NOT NULL,
    previous_sales_rep_id UUID,
    new_sales_rep_id      UUID,
    dead_letter_id        UUID,
    outcome               VARCHAR(16)  NOT NULL
        CONSTRAINT ck_lead_action_outcome CHECK (outcome IN ('SUCCES','ECHEC')),
    detail                TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_lead_action_lead ON lead_action (lead_id, created_at);
```

**`lead_id` porte une cle etrangere**, contrairement a `dead_letter` dont la reference peut
designer un lead inexistant parce que le message est corrompu : ici l'action part d'un lead
qu'on vient de lire.

**Les deux references de commerciaux n'en portent pas.** Un commercial supprime ne doit pas
effacer l'histoire ; le journal affiche l'identifiant tel quel.

**`outcome` et `detail` existent pour le rejeu**, et c'est ce que `dead_letter` ne retient pas
aujourd'hui. Une reattribution vaut toujours `SUCCES` — la ligne n'est ecrite qu'apres le
commit de l'ecriture, donc un echec ne produit aucune ligne ; `ECHEC` ne concerne que le
rejeu d'un message mort, et `detail` porte alors le message d'erreur.

**`dead_letter_id`** est nul pour une reattribution ; sans lui, la timeline afficherait deux
fois un meme rejeu — une fois derive de `dead_letter.replayed_at`, une fois lu dans le
journal.

### L'index unique de `dead_letter`, paye au passage

```sql
CREATE UNIQUE INDEX uq_dead_letter_lead_pending
    ON dead_letter (lead_id) WHERE lead_id IS NOT NULL AND status = 'PENDING';
```

C'est la dette relevee dans la feuille de route, a payer ici parce qu'on ecrit la migration de
toute facon. **Partiel, et non global sur le contenu du message** : la livraison etant
at-least-once, une meme mort livree deux fois ecrirait deux lignes, mais une seconde mort
_apres_ un rejeu qui a de nouveau echoue est un fait reel qu'il faut garder. Restreindre aux
lignes `PENDING` distingue exactement ces deux cas — un lead n'a qu'une mort en attente
d'action humaine a la fois, et rejouer ou ecarter rouvre la place. L'index aligne le schema
sur un garde-fou deja ecrit en Java : `QualifiedLeadRelay` et `RoutedLeadRelay` ignorent les
leads portant une mort `PENDING`.

**Consequence obligatoire** : `DeadLetterJournal.enregistre` doit rattraper la violation de
contrainte et rendre la ligne existante, exactement comme la capture le fait pour son index
d'idempotence en F2. Sans cela, un doublon ferait remonter l'exception et le message
retournerait dans une DLQ qui n'a aucune DLX pour le rattraper.

## Le contrat d'API

```
POST /api/leads/{id}/reassign
{ "salesRepId": "…", "reason": "Depart en conge, dossier repris par Amine" }
→ 200  LeadDetail
```

Le chemin d'URL suit la ressource, le package suit la responsabilite : le controleur vit dans
`routing/` bien que le chemin commence par `/api/leads`.

**L'operateur vient du `Principal`, jamais du corps.** C'est deja la regle de
`DeadLetterController`, et un acteur transmis par le client serait un journal falsifiable.

La reponse est la fiche rechargee plutot qu'un `204` : cela evite un aller-retour et garantit
que l'ecran affiche l'etat reellement enregistre, pas celui qu'il croit avoir demande.

## Le flux, et ses refus

1. Charger le lead — absent : `RessourceIntrouvableException`, **404**.
2. Lead sans commercial (`QUALIFIED`, `DISCARDED`) : **409**.
3. Commercial vise inconnu, inactif, ou **d'une autre boutique** : **409**. Ce dernier
   controle n'est pas theorique — l'identifiant vient du client, et rien d'autre n'empecherait
   d'attribuer le lead d'une boutique au commercial d'une autre.
4. Commercial deja en place : **409**. Une reattribution sans changement polluerait le journal
   sans rien dire.
5. `RoutedLeadWriter.reattribue` — transaction propre, commit.
6. `LeadActionJournal` ecrit la ligne `REATTRIBUTION` / `SUCCES`.
7. Rendre la fiche rechargee.

Les refus 2 a 4 passent par une `ReattributionImpossibleException` unique, mappee sur `409`
dans `ApiExceptionHandler` a cote de `DejaTraiteException` et `DernierCommercialException`.

**Le journal s'ecrit apres le commit de l'ecriture, pas avant.** L'ordre inverse laisserait
une ligne affirmant un changement qui n'a pas eu lieu. Le risque assume est symetrique et
moindre : une panne entre les deux etapes donne un lead reattribue sans trace, visible dans la
timeline comme une attribution sans action.

## Le rejeu et l'ecart rejoignent le journal

`DeadLetterReplayService.rejoue` et `.ecarte` prennent desormais un **motif** et ecrivent leur
ligne `REJEU` / `ECART` avec son `outcome` et son `dead_letter_id`. Les colonnes
`replayed_by` et `replayed_at` restent en place et continuent d'alimenter l'ecran du journal
des morts : elles ne mentent pas, il n'y a rien a migrer.

## La timeline

Deux ajouts a `LeadTimelineService`, qui reste strictement en lecture.

Un **septieme type, `REATTRIBUTION`**, place apres `ATTRIBUTION` dans `TimelineEventType` —
dont l'ordre de declaration est l'ordre du pipeline et sert a positionner les entrees non
datees.

Les entrees de rejeu viennent desormais de `lead_action` quand une ligne y reference la mort,
et **retombent sur `dead_letter.replayed_at`** sinon. Meme parti que `routed_at` en F9 :
l'historique anterieur a la migration n'est pas reinvente, il est affiche avec ce qu'on sait
de lui.

`monitoring/` lit une table de `routing/` : c'est deja ce qu'il fait pour `lead`,
`raw_lead_event` et `crm_sync_attempt`. L'invariant qui compte — il n'ecrit que `dead_letter`
— n'est pas entame.

## L'ecran

Un bouton « Reattribuer » dans le bloc du commercial de la fiche du lead ouvre une boite de
dialogue Material : la liste des commerciaux **actifs de la boutique du lead**
(`GET /api/admin/clients/{id}/sales-reps`, qui existe), le motif obligatoire, et — **si le
lead est `SYNCED`** — l'avertissement que l'ERP conservera l'ancien responsable.

Au succes, la fiche remplace son commercial et appelle `leadTimeline.recharge()`, la methode
rendue publique en F9 pour exactement ce moment. Pas de rechargement complet de la page ;
c'est aussi la raison pour laquelle l'endpoint de timeline est separe de `GET /api/leads/{id}`.

Le libelle et l'icone du nouveau fait vont dans les tables `FAITS` et `DETAILS` de
`lead-timeline.ts`, ou vit deja tout le vocabulaire d'interface. Les icones sont des ligatures
Material Symbols servies par l'origine — la CSP de production dit `font-src 'self'`.

Le journal des morts gagne le meme champ de motif sur ses deux gestes existants.

**Les decisions visuelles passent par les skills du plugin `ui-ux-pro-max`, invoquees avant
d'ecrire le code.**

## Ce que la feature ne fait pas

**Elle ne pousse rien vers l'ERP.** Un lead `SYNCED` reste chez Dolibarr avec l'ancien
responsable. Propager demanderait une methode de plus sur le port `CrmConnector`, donc une
extension du contrat que tous les adaptateurs devraient honorer, et elle retomberait droit sur
le trou d'etape que F14 traite. L'ecran le dit avant confirmation ; ce n'est pas masque.

**Elle deplace le tour de role, et c'est assume.** La rotation se lit dans la table `lead`
(`derniereAttributionParCommercial`) : le commercial qui recoit un lead reattribue parait
servi plus recemment, et cela influence les attributions suivantes. C'est coherent — il a bien
recu ce lead. Exclure les leads reattribues du calcul ferait mentir la charge reelle des
commerciaux.

**Elle n'ouvre pas d'attribution manuelle** d'un lead que le routage n'a pas encore traite, ni
de reattribution en lot, ni d'audit global des gestes de l'operateur.

## Les tests

En TDD, comme le reste du projet.

| Classe | Ce qui est verrouille |
| --- | --- |
| `ReattributionServiceTest` | les quatre refus — lead sans commercial, commercial inconnu ou inactif, commercial d'une autre boutique, commercial deja en place — et le cas passant |
| `RoutedLeadWriterTest` (etendu) | `reattribue` ne touche ni le statut ni `routed_at` |
| `LeadActionJournalTest` | la ligne porte acteur, motif, les deux commerciaux |
| `LeadReassignmentControllerTest` | 404 / 409 / 200, et l'acteur venant du `Principal` et non du corps |
| `LeadTimelineServiceTest` (etendu) | la reattribution a sa place ; un rejeu n'apparait qu'une fois |
| `DeadLetterJournalTest` | une mort livree deux fois n'ecrit qu'une ligne, sans lever |
| Karma | la boite refuse la validation sans motif ; l'avertissement `SYNCED` n'apparait que sur ce statut ; `recharge()` est appelee au succes |

Deux precautions heritees de la session du 1er septembre, non negociables ici. **Chaque
nouvelle classe de test qui ecrit en base rend la base comme elle l'a trouvee** — un
`@AfterEach` supprimant dans l'ordre des cles etrangeres. Et **avant de pousser**, les paquets
touches tournent avec `capture/` dans une seule commande, seul moyen de reproduire l'echec qui
a rendu la CI rouge apres F9 :

```bash
cd backend && ./mvnw test -Dtest='com.leadflow.capture.**.*Test,com.leadflow.routing.**.*Test,com.leadflow.monitoring.**.*Test' -DfailIfNoSpecifiedTests=false
```

## Le decoupage

Huit taches, dans un ordre ou chacune se verifie seule :

1. La migration `V8`, l'entite `LeadAction` et son depot.
2. `LeadActionJournal`, et le rattrapage de contrainte dans `DeadLetterJournal`.
3. `RoutedLeadWriter.reattribue`.
4. `ReattributionService` et ses quatre refus.
5. `LeadReassignmentController` et le mapping `409`.
6. Le motif sur le rejeu et l'ecart d'un message mort.
7. La timeline : septieme type, et la deduplication des rejeux.
8. L'ecran Angular — dialogue, avertissement `SYNCED`, rechargement de la chronologie.

Branche `feature/f10-reattribution-manuelle`, fusion locale en `--no-ff` dans `main` apres
recette, branche conservee.

## Ce qui reste hors de F10

La **recette visuelle a l'ecran** est faite par l'utilisateur en fin de feature.

La dette du **secret HMAC de `R__demo_data.sql`**, fausse depuis F7.2, reste ouverte et
demande une decision de l'utilisateur. En attendant, faire tourner le secret depuis l'ecran
« Boutiques » apres chaque rejeu de la migration de demonstration, avant d'envoyer un lead.
