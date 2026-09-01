# État du dépôt à l'arrêt — F9 livrée, recettée et fusionnée

Session du 31 août au 1er septembre 2026. Elle a **implémenté F9 en entier**, des huit tâches
du plan jusqu'à la fusion dans `main`, avec la recette à l'écran faite par l'utilisateur.

## L'état du dépôt

| | |
| --- | --- |
| Branche courante | `main` |
| Dernier commit | `6966de7` (fusion de F9, en `--no-ff`) |
| Arbre de travail | **propre** |
| Poussé ? | **oui** — `main` et `feature/f9-timeline-du-lead` |
| Branche de la feature | **conservée**, comme toutes les précédentes |
| Migrations | **sept** — `V7__lead_routed_at.sql` est appliquée en base de développement |
| CI | **en echec sur `main`** — run `33455680895`, job `Backend — tests et paquet` |

## Ce que F9 a livré

Neuf commits, huit tâches. `GET /api/leads/{id}/timeline` rend la chronologie d'un lead,
**dérivée en lecture seule** de quatre tables existantes — `raw_lead_event`, `lead`,
`crm_sync_attempt`, `dead_letter`. Aucune table d'événements n'a été créée : une seule donnée
était réellement neuve, `lead.routed_at`.

L'écran `leads/:id` porte désormais une carte « Chronologie » entre la fiche du lead et les
synchronisations ERP.

### Les trois décisions à ne pas perdre

**`V7` est nullable et sans remplissage rétroactif.** L'attribution n'était datée nulle part
avant F9, et `updated_at` ne la remplace pas : il vaut la date d'attribution pour un lead
resté `ROUTED`, mais celle de la synchronisation pour un lead `SYNCED`. Remplir l'historique
depuis cette colonne aurait inventé une date pour tout lead déjà synchronisé.

**Une entrée non datée garde sa place dans le pipeline** au lieu d'être rejetée en tête ou en
queue, comme le ferait un comparateur ordinaire. L'ordre des étapes est connu même quand leur
date ne l'est pas. C'est le seul point subtil du service, et il est verrouillé par un test.

**Le backend rend des faits typés, jamais des phrases.** La mise en français des six types et
des clés de `details` vit dans le composant Angular. C'est la règle inverse de
`replayWarning`, qui reste calculé côté serveur parce qu'il encode une décision et non un
libellé.

### Le visuel, décidé avant le code

Passé par `ui-ux-pro-max` comme l'impose le plan. Un échec porte **trois signes** — pastille
`--lf-echec` sur `--lf-echec-fond` (6,4:1), icône Material Symbols servie par l'origine, et
le mot « Echec » écrit. Une date absente se lit « date inconnue » en italique dans le ton
neutre, **jamais dans la couleur d'un échec** : c'est une absence assumée, pas une panne
d'affichage. La densité suit celle du dashboard, les huit faits d'un lead en échec tenant
sans défilement.

Point que le plan supposait à tort : **le dashboard n'a pas de thème sombre**,
`color-scheme: light` est imposé dans `styles.scss`. Un seul jeu de couleurs suffit.

## Trois erreurs du plan, corrigées en cours de route

Le plan était juste sur l'architecture et faux sur trois détails d'API. Les trois ont été
vérifiées contre le code, pas devinées.

1. `CrmSyncAttemptStatus.FAILURE` n'existe pas — la valeur est **`FAILED`**.
2. Le test posait un `UUID.randomUUID()` comme commercial, alors que
   `lead.assigned_sales_rep_id` porte une **clé étrangère** : le fixture crée maintenant un
   vrai `SalesRep`.
3. Le plus instructif : le fixture datait tout en `2026-08-31T10:00Z`, mais la date de
   qualification est `lead.created_at`, **posée par `@PrePersist`** à l'insertion et donc
   impossible à choisir. La qualification tombait en fin de chronologie et trois tests
   échouaient. Le fixture est désormais **ancré sur l'instant courant** — capture une heure
   avant, attribution et synchronisation quelques minutes après.

Leçon transposable aux features suivantes : **tout fixture qui mélange des dates choisies et
`created_at` doit s'ancrer sur `Instant.now()`**, jamais sur une date fixe.

## Les vérifications

| Lot | Résultat |
| --- | --- |
| `monitoring/**` | 65 tests |
| `routing/**` + `qualification/**` | 150 tests |
| `capture/**` + `crm/**` | 142 tests |
| `tenant/**` + `common/**` + `config/**` | 75 tests |
| Frontend | 48 tests Karma, ESLint, Prettier, `npm run build` |

Rejoués **sur l'arbre fusionné** avant la poussée : le frontend en entier et les 103 tests de
`monitoring/` et `routing/`. La suite backend se joue **par lots, jamais d'un bloc** — un run
complet n'a jamais survécu sur ce poste.


## ⚠️ La CI est rouge sur `main` — c'est la premiere chose a reprendre

**Le job `Backend — tests et paquet` echoue depuis la fusion de F9** (run `33455680895`, et
de nouveau sur le run du document d'etat : c'est le meme code). Les deux jobs frontend
passent ; `fumee` n'est pas execute, puisqu'il depend des deux premiers.

### La cause, identifiee et non corrigee

```
update or delete on table "raw_lead_event" violates foreign key constraint
"lead_raw_event_id_fkey" on table "lead"
```

Les trois classes de test ajoutees par F9 — `LeadTimelineServiceTest`,
`LeadTimelineControllerTest` et `RoutedLeadWriterTest` — **creent des lignes `lead` et ne les
nettoient pas**. La suite complete partage une seule base Testcontainers : quand
`LeadCaptureIntegrationTest` vide `raw_lead_event`, les leads laisses derriere referencent
encore ces lignes, et Postgres refuse la suppression. Vingt-et-un tests de `capture/`
tombent en cascade (`LeadCaptureIntegrationTest`, `PendingEventRelayTest`,
`RawLeadEventIdempotenceTest`).

### Pourquoi il n'a pas ete vu avant la poussee

Deux filets avaient un trou, et c'est le meme trou :

- **La suite backend a ete jouee par lots**, comme l'exige ce poste — un run complet n'y a
  jamais survecu. Le defaut n'apparait que lorsque `capture/` tourne **dans le meme JVM** que
  `monitoring/`, ce qu'aucun lot ne reproduit.
- **Le crochet `pre-push` ne joue pas `./mvnw verify`**, par construction : il ne fait que les
  trois controles frontend. Il ne pouvait donc pas l'arreter.

C'est exactement le trou que la consigne « par lots » laisse ouvert, et la CI l'a rattrape.
**A retenir pour toute feature qui ajoute des tests ecrivant en base : un lot vert ne dit
rien de la suite complete.**

### La correction a faire

Une branche `fix/f9-nettoyage-des-tests`, et un `@AfterEach` dans chacune des trois classes
qui supprime **dans l'ordre des cles etrangeres** ce que le test a cree : morts
(`dead_letter`), tentatives (`crm_sync_attempt`), leads, evenements bruts, commercial,
boutique. Les classes de test existantes le font deja — c'est leur modele qu'il faut suivre,
pas un nettoyage global qui effacerait les donnees d'autres tests.

Verification qui tranche, et qui n'a pas ete faite : `./mvnw verify` **en entier**. S'il ne
survit pas sur ce poste, jouer au minimum `capture/` et `monitoring/` **dans la meme
commande**, ce qui suffit a reproduire l'echec.

**F9 elle-meme n'est pas en cause** : la fonctionnalite est recettee a l'ecran et le code de
production est intact. Ce sont les tests qui ne rendent pas la base comme ils l'ont trouvee.

## L'état de cette machine à l'arrêt

**Deux conteneurs tournent** — `leadflow-postgres` et `leadflow-rabbitmq`, sains. Pour les
arrêter : `docker compose down`, **jamais `down -v`** — le volume porte l'historique de
démonstration de toutes les sessions.

**Le backend et le dashboard écoutent encore**, bien que leurs tâches d'arrière-plan aient été
arrêtées : le JVM enfant de `mvnw` survit à son parent, comme aux sessions précédentes.
PID 18020 sur `:8090`, PID 6124 sur `:4200`. L'assistant n'a pas la permission de tuer un
processus sur ce poste ; sous Git Bash il faut doubler les slashs :
`taskkill //PID 18020 //F`.

## Par quoi reprendre

**D'abord la CI rouge**, voir la section dediee plus haut : le nettoyage des trois classes de
test de F9. Rien d'autre ne devrait partir avant que `main` soit vert.

**Ensuite F10**, la suite de la feuille de route. Deux points hérités à ne pas perdre :

- **F10 prend `V8`**, pas `V7` — la feuille de route dit encore `V7`, et c'est la troisième
  fois que ce numéro glisse (`V6` avait été consommée par F11.2, `V7` l'est par F9).
- L'index unique sur `dead_letter` se paie **avec cette migration `V8`**.
- Le composant `lead-timeline` expose une méthode `recharge()` **publique et déjà écrite pour
  F10** : après une réattribution, la chronologie se rafraîchit seule, sans refaire tout le
  détail du lead. C'est aussi la raison pour laquelle l'endpoint est séparé de
  `GET /api/leads/{id}`.

L'ordre restant est inchangé : **F10 → F12 → F13 → F14**.

## Les dettes

**Toujours ouverte, et non traitée par F9** : le secret HMAC en clair de `R__demo_data.sql`
est faux. La correction demande de rechiffrer une valeur avec la clé maître de développement,
opération que le classificateur refuse à l'assistant. À trancher : soit l'utilisateur produit
la valeur, soit on retire la valeur en clair du fichier et on assume la rotation par
l'interface comme geste normal. En attendant, **après chaque rejeu de `R__demo_data.sql` il
faut refaire tourner le secret depuis l'écran « Boutiques »** avant d'envoyer un lead.

**Dans la feature qui touche déjà le code** : l'index unique de `dead_letter` avec `V8` de
F10 ; la recherche de tiers Dolibarr par email avec F14.

**Jamais, et à défendre à l'oral** : le mono-instance à quatre endroits, l'absence de
notification au commercial, le TLS et le nonce CSP.
