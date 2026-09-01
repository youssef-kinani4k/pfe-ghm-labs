# Etat du depot a l'arret — la CI est verte, F10 est la prochaine feature

Session courte du 1er septembre 2026. Elle n'a fait qu'une chose : **reparer la CI rouge
laissee par la fusion de F9**, la verifier deux fois, et fusionner. Aucune fonctionnalite
n'a bouge.

## L'etat du depot

| | |
| --- | --- |
| Branche courante | `main` |
| Dernier commit | `d2d7143` (fusion du correctif, en `--no-ff`) |
| Arbre de travail | **propre** |
| Pousse ? | **oui** — `main` et `fix/f9-nettoyage-des-tests`, rien en avance |
| Branche du correctif | **conservee**, comme toutes les precedentes |
| Migrations | **sept** — inchangees, `V7__lead_routed_at.sql` est la derniere |
| CI | **verte sur `main`** — run `33547218528`, les quatre jobs, `fumee` compris |

## Ce que la session a corrige

Le job `Backend — tests et paquet` echouait depuis la fusion de F9. La cause etait connue et
ecrite dans le document d'etat precedent : les trois classes de test ajoutees par F9 —
`LeadTimelineServiceTest`, `LeadTimelineControllerTest` et `RoutedLeadWriterTest` — creaient
des lignes `lead` sans les supprimer. La suite complete partage **une seule base
Testcontainers** : quand `capture/` vidait `raw_lead_event` dans le meme JVM, Postgres
refusait la suppression (`lead_raw_event_id_fkey`) et vingt-cinq tests de `capture/`
tombaient en cascade.

La correction est un `@AfterEach` par classe, supprimant **dans l'ordre des cles etrangeres**
ce que le test a cree, sur le modele des classes voisines (`LeadDetailServiceTest` et les
autres du meme paquet) :

| Classe | Ordre de suppression |
| --- | --- |
| `LeadTimelineServiceTest` | `dead_letter`, `crm_sync_attempt`, `lead`, `raw_lead_event`, commercial, boutique |
| `LeadTimelineControllerTest` | `lead`, `raw_lead_event`, boutique |
| `RoutedLeadWriterTest` | `lead`, `raw_lead_event`, commercial, boutique |

**Aucun code de production n'a ete touche.** F9 n'etait pas en cause : ce sont les tests qui
ne rendaient pas la base comme ils l'avaient trouvee.

Note sur le nettoyage global : les classes existantes appellent `deleteAll()` et non une
suppression ciblee. C'est leur modele qui a ete suivi, parce que la suite ne tourne pas en
parallele — un `@AfterEach` global n'y efface donc jamais les donnees d'un autre test en
cours.

## La lecon, deja ecrite mais desormais payee

**Un lot vert ne dit rien de la suite complete.** La suite backend se joue par lots sur ce
poste — un run complet n'y a jamais survecu — et le defaut n'apparaissait que lorsque
`capture/` tournait dans le meme JVM que `monitoring/`, ce qu'aucun lot ne reproduisait. Le
crochet `pre-push` ne pouvait pas l'arreter non plus : il ne joue que les trois controles
frontend, par construction.

Pour toute feature qui ajoute des tests ecrivant en base : **jouer au minimum `capture/` et
le paquet touche dans une seule commande** avant de pousser.

## Les verifications

| Verification | Resultat |
| --- | --- |
| `capture/` + `monitoring/` + `routing/` dans **un seul JVM**, en local | **156 tests, 0 echec** |
| CI sur `fix/f9-nettoyage-des-tests` — run `33546173386` | 4 jobs verts |
| CI sur `main` apres fusion — run `33547218528` | 4 jobs verts |

La commande locale qui reproduit l'echec, a reutiliser :

```bash
cd backend && ./mvnw test -Dtest='com.leadflow.capture.**.*Test,com.leadflow.monitoring.**.*Test,com.leadflow.routing.**.*Test' -DfailIfNoSpecifiedTests=false
```

Elle prend une dizaine de minutes sur ce poste.

## L'etat de cette machine a l'arret

**Plus propre qu'aux sessions precedentes.** Aucun conteneur ne tourne — les conteneurs
Testcontainers se sont retires seuls, et `docker compose` n'a pas ete monte de la session.
**Aucun processus n'ecoute sur `:8090` ni sur `:4200`** : ni backend ni dashboard a arreter.

Le demon Docker, lui, tourne : il etait **arrete au demarrage de la session** et a ete relance
(`Docker Desktop.exe`) parce que Testcontainers en depend. A verifier au debut de la prochaine
session — `docker info` — avant de lancer quoi que ce soit de backend.

Rappel qui n'a pas change : pour arreter la pile de developpement, `docker compose down`,
**jamais `down -v`** — le volume porte l'historique de demonstration de toutes les sessions.

Deuxieme rappel d'environnement : **le crochet `pre-push` depasse deux minutes** (ESLint,
Prettier et les 44 tests Karma). Un `git push` coupe trop tot ressemble a un echec sans en
etre un ; lui laisser dix minutes.

## Par quoi reprendre

**F10 — reattribution manuelle et journal d'actions.** Une session estimee. C'est le dernier
manque d'exploitation du produit : un lead attribue au mauvais commercial ne se corrige
aujourd'hui que par un rejeu depuis le journal des morts, ou en base.

Trois points herites a poser au brainstorming :

- **La migration est `V8`, pas `V7`.** La feuille de route dit encore `V7` et c'est faux d'un
  cran depuis F9 ; le numero a deja glisse trois fois.
- **L'index unique sur `dead_letter` se paie avec cette `V8`** — trois lignes dans une
  migration qu'on ecrit de toute facon.
- **`lead-timeline.recharge()` est publique et deja ecrite pour F10** : apres une
  reattribution, la chronologie se rafraichit seule, sans refaire tout le detail du lead.
  C'est aussi la raison pour laquelle l'endpoint de timeline est separe de
  `GET /api/leads/{id}`.

L'ordre restant est inchange : **F10 -> F12 -> F13 -> F14**. F10 avant F12 parce que la table
d'audit de F10 devient une source d'agregats ; F13 et F14 sont independants et deplacables.

## Les dettes

**Ouverte, et elle demande une decision de l'utilisateur** : le secret HMAC en clair de
`R__demo_data.sql` est faux depuis F7.2. Il devait etre corrige pendant F9 et ne l'a pas ete —
rechiffrer une valeur avec la cle maitre est une operation que le classificateur refuse a
l'assistant. Deux issues : soit l'utilisateur produit la valeur chiffree, soit on retire la
valeur en clair et on assume la rotation par l'interface comme geste normal. En attendant,
**apres chaque rejeu de `R__demo_data.sql`, faire tourner le secret depuis l'ecran
« Boutiques »** avant d'envoyer un lead.

**Dans la feature qui touche deja le code** : l'index unique de `dead_letter` avec `V8` de
F10 ; la recherche de tiers Dolibarr par email avec F14.

**Jamais, et a defendre a l'oral** : le mono-instance a quatre endroits, l'absence de
notification au commercial, le TLS et le nonce CSP.
