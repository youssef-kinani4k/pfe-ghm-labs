# F13 livree, non fusionnee — etat pour la reprise

Session du 5 septembre 2026. F13 est **complete, revue et corrigee**, mais **ni recettee a
l'ecran ni fusionnee** : ces deux gestes reviennent a l'utilisateur.

## L'etat du depot

| | |
| --- | --- |
| Branche courante | `feature/f13-analytics-graphiques`, **21 commits** depuis `ac4c9c4` |
| `main` | inchangee, a `ac4c9c4` |
| Arbre de travail | **propre** |
| Migrations | **dix** — `V10__analytics_index.sql` est la derniere |
| Suite backend | `monitoring` **103/103**, `config` et `common` **29/29** |
| Suite frontend | **72/72**, ESLint et Prettier verts |
| Build | reussi, chunk `analyse` **paresseux** a 200,69 ko brut / 59,20 ko compresse |

**La suite backend n'a pas ete rejouee en entier**, et c'est assume : aucun commit de la
branche ne touche `capture`, `qualification`, `routing`, `crm`, `tenant` ni `notification` —
`git diff --stat ac4c9c4..HEAD -- backend/src/main/java` ne montre que `monitoring/`, plus
`AnalyticsProperties` dans `config/` et une methode ajoutee a `ApiExceptionHandler` dans
`common/`. Le job `backend` de la CI joue `./mvnw verify` entier au push, y compris sur les
branches `feature/**`.

## Ce que F13 livre

Un ecran « Analyse » (`/analyse`, onzieme ecran du dashboard) portant **trois series
quotidiennes**, servies par un endpoint unique.

**Le monitoring ne savait dire que le present.** `StatsView` servait deja tout l'ecran
d'accueil en un appel, avec de vraies requetes de comptage — ce qui manquait n'etait pas le
comptage, c'etait le temps.

| Carte | Forme | Question a laquelle aucun compteur ne repondait |
| --- | --- | --- |
| Volume capture | aire + ligne : leads produits / ecartes | « en perd-on plus qu'avant ? » |
| Delai capture -> ERP | deux lignes : mediane et p95, en secondes | « le pipeline tient-il la charge ? » |
| Analyse d'intention | **aire empilee a 100 %** : Gemini / lexique | « le mode degrade s'est-il installe ? » |

Le taux d'echec ERP par connecteur a ete **ecarte** bien que la feuille de route le listat :
`ConnectorView` le sert deja a l'ecran des connecteurs, et le garde-fou de la spec interdit
une figure qui redit un compteur affiche ailleurs.

## Ce qu'il faut savoir avant d'y toucher

**`SeriesRepository` est le premier repository natif du projet**, et deux contraintes
independantes l'imposent. JPQL n'a ni `date_trunc` ni `AT TIME ZONE`, et le seul contournement
global — `hibernate.jdbc.time_zone` — changerait la lecture de tous les `Instant` du projet
pour resoudre le besoin de trois requetes ; il n'a pas ete touche. Et `percentile_cont`
n'existe pas davantage en JPQL. Le prix du natif : **Hibernate ne valide plus ces requetes au
demarrage**, ce qui rend les tests contre un vrai Postgres obligatoires et non facultatifs.

**La regle de comblement est le coeur de la feature.** Une journee sans donnee n'a pas de ligne
en base, et un graphique tracerait une droite par-dessus. Le service comble, et **la valeur de
comblement differe selon la figure** : zero pour le volume et les intentions — « aucun lead
capture ce jour-la » est un fait — et **`null` pour les delais**, parce que « aucun lead
synchronise » ne veut pas dire « delai de zero seconde » : mis a zero, la courbe dessinerait
une chute vers le bas, soit l'inverse du sens. Ce `null` survit du SQL jusqu'au `spanGaps:
false` du canvas, et la revue finale l'a suivi de bout en bout.

**Le fuseau de regroupement est nomme, jamais deduit.** `date_trunc` decoupe selon le fuseau de
la session — UTC dans le conteneur — si bien qu'un lead recu a 00 h 30 heure locale tomberait
dans la journee de la veille. `leadflow.analytics.fuseau` vaut `Europe/Paris` par defaut et
est **global a l'instance**, comme les reglages du relais SMTP de F12.

**Les delais sont ancres sur le jour de la synchronisation, pas de la capture.** Le point est
alors definitif des que le jour est passe ; l'ancrage inverse ferait **s'ameliorer la courbe
quand ca va mal**, un lead jamais synchronise n'y apparaissant jamais. Le delai se compte
jusqu'au **premier** succes : un rejeu ajoute un succes tardif, et prendre le dernier ferait
afficher trois jours pour un lead synchronise en deux minutes.

**Chart.js 4.5.1 est confine.** Un seul fichier l'importe, `graphique-ligne.ts`, et l'ecran ne
le connait pas — il passe des libelles, des series et des couleurs. La route est en
`loadComponent`, donc la bibliotheque vit dans le chunk de la feature et **n'entre pas dans
celui de l'accueil**. Pas de `ng2-charts` : un enrobage ajouterait une peerDependency a faire
correspondre a chaque montee d'Angular, ce qui etait precisement la crainte justifiant
l'absence de bibliotheque.

**Les couleurs sont resolues avant d'atteindre le canvas.** Un canvas ne resout pas les
`var(--...)` : passer un jeton CSS directement aurait rendu les series invisibles, en echec
muet. L'ecran les resout par `getComputedStyle` et ne passe que des valeurs litterales.

## Ce que la recette doit eprouver — elle reste a faire

Monter la pile, puis regarder :

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm start
```

1. `/analyse` s'ouvre depuis la navigation, et **l'icone est visible** — une icone absente veut
   dire un nom de ligature inconnu de la police servie.
2. Les trois boutons de periode changent les figures, et l'axe passe de 7 a 30 a 90 points.
3. Le selecteur de boutique filtre.
4. Sur une instance ou peu de leads ont ete synchronises, **la courbe des delais s'interrompt**
   au lieu de tomber a zero.
5. Une boutique sans aucun lead affiche l'etat vide, **pas trois canvas plats**.
6. Les couleurs sont lisibles et coherentes avec le reste de la console.

## Les defauts trouves en chemin, et par quoi

Aucun n'aurait ete vu par la seule lecture du code.

**Le balayage de conflits, avant la premiere ligne.** Deux tests du plan ne pouvaient pas
passer : l'un attendait une `IllegalArgumentException` la ou `ZoneId.of` leve une
`DateTimeException`, l'autre appelait `Lead.setCreatedAt(...)`, qui n'existe pas — `createdAt`
vient de `BaseEntity`, en `@Getter` seul et `updatable = false`. Et le plan passait
`var(--lf-succes)` a un canvas, ce qui aurait donne des series invisibles.

**La premiere tache d'implementation.** Les fabriques d'entites du plan etaient fausses sur
trois champs : `Client.crmProviderId` est obligatoire, `RawLeadEvent.source` aussi, et
`payload` est une `Map<String,Object>` et non une `String`.

**Une consigne de ma part etait fausse.** « Francais sans accents » vaut pour le code et
`CLAUDE.md`, pas pour `docs/*.md`, qui s'ecrivent avec accents. L'implementeur l'a signale au
lieu de trancher seul ; la section ajoutee a `monitoring-api.md` a ete reaccentuee.

**La revue finale, et c'est le defaut le plus interessant.** La spec definit la carte des
intentions comme une **aire empilee a 100 %** ; l'ecran tracait deux aires en valeurs absolues
tout en annoncant « Part des leads analyses par le modele ». Le texte promettait une part, le
canvas montrait des compteurs. **La derive vient du plan et non du code** : le type
`SerieGraphique` qu'il definit n'a jamais porte de notion d'empilement, si bien que douze
revues de tache ont valide une conformite au plan sans qu'aucune ne compare le plan a la spec.
C'est le defaut que seule une revue de branche pouvait voir.

**La re-revue de la correction.** Le test cense verrouiller la borne haute des requetes est un
**faux verrou** : il passerait a l'identique sans le correctif. Voir la section suivante.

## Trois dettes nommees, et un constat ouvert

**La CTE des delais n'est pas bornee.** `premier_succes` fait `min(attempted_at) group by
lead_id` **sans aucun filtre** ; le predicat de date porte sur le resultat de l'agregat et
n'est pas poussable dedans. Le cout de cette requete est donc proportionnel a **tous les succes
depuis toujours**, independamment de `jours` : a volume constant, l'ecran ralentira avec l'age
de l'instance. Et `idx_crm_sync_attempt_success_at` ne sert pas un `group by lead_id`. Le vrai
bornage demande un `where` dans la CTE plus un `not exists` de succes anterieur — sa propre
tache. La dette est ecrite dans le Javadoc de `SeriesRepository` et dans `CLAUDE.md`. Elle ne
l'est **pas** dans `V10`, dont le commentaire surestime encore ce que l'index apporte : Flyway
calcule son empreinte sur le contenu brut du fichier, commentaires compris, et modifier une
migration appliquee ferait echouer le demarrage.

**Le constat ouvert, a traiter avant ou juste apres la fusion.** Le test
`uneLigneDateeDeDemainNApparaitPasDansLaSerie` n'eprouve pas ce qu'il pretend :
`SeriesService` construit sa sortie par `calendrier.stream().map(j -> volumes.get(j))`, donc
une ligne datee de J+2 est rangee sous une cle absente du calendrier et ignoree, **borne haute
ou pas**. Retirer la clause `< :jusqu` ne ferait pas echouer ce test, et le message du commit
`8c9cdb3` affirme donc une garantie que le code ne donne pas. Le code livre est bon — la borne
empeche un balayage illimite vers le futur — mais il faut soit deplacer le test au niveau du
repository, ou il discriminerait reellement, soit cesser d'affirmer qu'il verrouille la regle.

**L'aire empilee a 100 % masque la taille de l'echantillon.** Une journee ou une seule analyse
a eu lieu affiche « 100 % Gemini », visuellement indiscernable d'une journee a 500 leads, et
aucun total absolu n'apparait sur cette carte. La forme demandee par la spec corrige bien le
mensonge du libelle, mais introduit le defaut classique de cette forme sur les petits
echantillons. Porter le total du jour dans l'infobulle le reglerait.

## La suite

Les trois features restantes, dans l'ordre, avec leur numerotation corrigee — le creneau F12 de
la feuille de route avait ete pris par la notification du commercial, decalant tout d'un cran :

- **F14 — rotation HMAC a fenetre de transition.** Une session courte, une migration. Le
  defaut repare : aujourd'hui `tourneLeSecret` remplace le secret a l'instant du commit, et
  chaque lead est refuse en `401` jusqu'a ce que le client redeploie son site.
- **F15 — pivot ERP en carte de references.** La plus lourde : elle touche `crm/model`, les
  deux adaptateurs, `monitoring/dto` et le frontend, et ouvre la propagation d'une
  reattribution jusqu'a l'ERP.
- Puis, hors feuille de route : le TLS, le nonce CSP, le passage multi-instance, un second
  canal de notification, le recalcul retroactif des scores.

Et, plus petit : le vrai bornage de la CTE des delais, et le total du jour dans l'infobulle de
la carte des intentions.
