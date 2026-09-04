# F10 livree et fusionnee — etat pour la reprise

Session des 3 et 4 septembre 2026. F10 est **complete, recettee a l'ecran, fusionnee dans
`main` et poussee**. Le depot est propre et synchronise avec `origin`.

## L'etat du depot

| | |
| --- | --- |
| Branche courante | `main`, synchronisee avec `origin/main` |
| Dernier commit | `bc34fbb` — la fusion de F10, en `--no-ff` |
| Arbre de travail | **propre** |
| Migrations | **huit** — `V8__lead_action.sql` est la derniere |
| Suite backend | **455/455**, `BUILD SUCCESS` |
| Suite frontend | **57/57**, ESLint et Prettier verts |
| CI | quatre jobs verts sur `6b227df`, script de fumee compris |
| Branche de la feature | `feature/f10-reattribution-manuelle`, **conservee** |

## Ce que la session a produit

Les taches 8, 9 et 10 du plan de F10, puis la recette et deux correctifs qu'elle a fait
apparaitre.

| Commit | Contenu |
| --- | --- |
| `3f33523` | T8 — reattribuer un lead depuis sa fiche, chronologie rechargee |
| `8e1581b` | T9 — le journal des morts demande un motif au lieu d'un `confirm()` |
| `6f3ae9e` | T10 — la documentation : `CLAUDE.md` et `docs/monitoring-api.md` |
| `61d7b11` | **Correctif de recette** — `V8` deduplique avant de poser son index |
| `6b227df` | **Correctif de recette** — la chronologie nomme les commerciaux |
| `bc34fbb` | la fusion dans `main` |

## Les deux defauts trouves en recette

Ils meritent d'etre connus, parce qu'aucun des deux n'etait visible autrement que par un
essai a l'ecran.

**`V8` empechait le backend de demarrer sur une base ayant de l'historique.** L'index unique
partiel `uq_dead_letter_lead_pending` refusait de se creer : un lead portait **deux morts
`PENDING`**, de charges utiles differentes, a quatorze secondes d'ecart, sans rejeu entre
elles. Le relais republie avant que la premiere mort ne soit journalisee, et le message
republie meurt a son tour — le garde-fou Java est au mieux, pas atomique. La CI ne pouvait
pas le voir : elle part d'une base vide. Arbitrage de l'utilisateur : **garder l'index**, et
faire nettoyer la migration avant de le poser, en gardant la mort la plus recente et en
passant les plus anciennes en `DISCARDED` plutot qu'en les supprimant.

**La chronologie affichait les commerciaux en UUID brut.** C'etait la dette relevee a la
tache 4 et laissee en suspens ; elle est devenue visible des la premiere recette. Les noms
sont desormais resolus cote serveur, en une seule requete, et un commercial introuvable garde
son identifiant — `lead_action` ne porte aucune cle etrangere vers `sales_rep`, pour qu'une
suppression n'efface pas l'histoire.

## Le constat non corrige

**La ligne « Attribution » de la chronologie montre le titulaire courant**, pas celui de
l'attribution d'origine : apres une reattribution, elle affiche deja le nouveau. Elle est
derivee de `lead`, qui ne garde aucun historique de la premiere attribution. La ligne
« Reattribution » dit bien l'ancien, mais lu de haut en bas le recit se contredit. Le corriger
demanderait de dater et de stocker l'attribution d'origine — donc une migration.

## La tache suivante, deja concue et validee : la dette de F7.2

Le brainstorming a ete fait et le design **approuve**, mais **rien n'a ete execute**. Il est
repris ici tel quel.

Le probleme : dans `backend/src/main/resources/db/dev/R__demo_data.sql`, le secret HMAC ecrit
en clair dans le commentaire **ne correspond pas** au chiffre pose dans `hmac_secret`. Signer
avec lui rend `401` — et comme les cinq causes de refus de la capture rendent volontairement
le meme code, rien n'indique d'ou vient l'echec.

Ce que la verification a etabli : **rien d'automatise n'en depend**. `SecretCipherTest` ne
s'en sert que comme texte quelconque a chiffrer, `scripts/smoke-prod.sh` cree sa propre
boutique et lit le secret rendu a la creation, et les 455 tests partent d'une base vierge sans
jamais charger `db/dev`. Les seuls consommateurs sont `docs/webhook-integration.md` et la
verification manuelle. L'impact est donc borne mais reel : toute feature dont la recette
demande d'injecter un lead frais par le webhook butera dessus.

Le design retenu, en trois temps :

1. **Tirer un secret neuf** — une valeur hexadecimale de 32 octets, chiffree **avec le code du
   projet** (un test jetable qui instancie `SecretCipher` avec la cle maitre de
   `application-dev.yml`, imprime le couple, puis est supprime). Surtout pas `openssl` ni un
   script Python : le format est `base64(IV‖chiffre‖tag)` avec un IV de douze octets prefixe,
   et une reimplementation subtilement differente ne se verrait qu'a l'execution, sous la
   forme d'un `401` muet. L'option « dechiffrer l'existant » a ete ecartee par l'utilisateur.
2. **Remplacer les deux valeurs ensemble** dans `R__demo_data.sql`, et la meme valeur dans
   `docs/webhook-integration.md` (ligne 109), en retirant l'encadre « faux depuis F7.2 ».
3. **Poser un garde-fou permanent**, et c'est la vraie reparation :
   `backend/src/test/java/com/leadflow/DonneesDeDemoTest.java` lit `R__demo_data.sql` et
   `application-dev.yml` depuis le classpath, extrait le clair documente et le chiffre stocke,
   dechiffre, et asserte l'egalite — pour `hmac_secret` **et** pour `crm_config`, meme dette
   dans le meme bloc de commentaires. Test unitaire pur, sans conteneur. La dette a dure deux
   semaines parce que rien ne verifiait ; corriger la valeur sans ce test reprogrammerait le
   meme incident.

Branche `fix/f7.2-secret-demo-coherent`, fusionnee en `--no-ff`.

**La consequence acceptee** : `R__demo_data.sql` est une migration **repetable**, donc changer
son contenu la rejoue au prochain demarrage en `dev`, et son `ON CONFLICT DO UPDATE` reecrira
la cle publique et le secret de la boutique de demonstration — ecrasant toute rotation faite
depuis l'ecran « Boutiques ».

**La verification qui tranche** : le test seul, puis la preuve de bout en bout — pile de dev
relancee, un vrai webhook signe avec la nouvelle valeur, et un `202` attendu. C'est le seul
controle qui dise que la documentation redevient vraie.

## L'etat de la machine a l'arret

La pile de developpement est **arretee** : `docker compose down`, sans `-v`, donc le volume
Postgres garde l'historique de demonstration de toutes les sessions. Rien n'ecoute sur `:8090`
ni sur `:4200`.

Le demon Docker a du etre **redemarre de force** pendant la session : son moteur rendait `500`
sur ses propres routes, y compris `docker info` et `docker ps`, ce qui faisait echouer
`./mvnw verify` avec 244 erreurs et zero `Failure`. La lecon vaut pour les prochaines fois :
**`docker info` qui repond ne prouve rien** — un `docker run --rm hello-world` est le seul
controle qui dise que le moteur sait reellement creer un conteneur.

La base porte les traces de la recette : cinq lignes dans `lead_action`, deux leads
reattribues, un message ecarte et deux rejoues. Elles sont voulues et documentent la recette ;
rien ne les nettoie.
