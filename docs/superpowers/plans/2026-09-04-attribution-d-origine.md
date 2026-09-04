# La chronologie nomme le titulaire d'origine — etat pour la reprise

Session du 4 septembre 2026, seconde tache. Le constat non corrige laisse par F10 est
**corrige, recette a l'ecran par l'utilisateur, fusionne dans `main` et pousse**.

## L'etat du depot

| | |
| --- | --- |
| Branche courante | `main`, synchronisee avec `origin/main` |
| Dernier commit | `5c01abe` — la fusion, en `--no-ff` |
| Arbre de travail | **propre** |
| Migrations | **huit**, inchangees — aucune n'a ete necessaire |
| Suite backend | **460/460**, `BUILD SUCCESS` |
| Crochet `pre-push` | ESLint, Prettier et les 44 tests frontend verts |
| Branche de la correction | `fix/f10-attribution-d-origine`, **conservee** |

## Le defaut

`LeadTimelineService.attribution()` construisait son entree depuis
`lead.assigned_sales_rep_id`, qui ne retient que le **dernier** titulaire en date. Apres une
reattribution, la ligne « Attribution » affichait donc deja le nouveau, et le recit se
contredisait lu de haut en bas : « Attribution : Amina », puis « Reattribution : ancien
commercial Karim, nouveau commercial Amina ».

## La correction, et pourquoi elle ne demande pas de migration

L'etat de fin de F10 annoncait qu'il faudrait une neuvieme migration. C'etait faux, pour la
meme raison que la dette de F7.2 l'etait : la donnee existait deja. `lead_action` porte
`previous_sales_rep_id`, donc le titulaire d'origine se lit du journal — c'est le
`previousSalesRepId` de la **premiere** reattribution, et le titulaire courant a defaut de
toute reattribution.

**La derivation est exacte, pas approchee.** F10 a introduit ensemble la reattribution et sa
trace, donc il n'existe aucune reattribution non journalisee ; et `ReattributionService`
refuse en `409` un lead sans commercial, donc la colonne ne peut pas etre nulle sur une ligne
`REATTRIBUTION`. Le journal etant deja charge et deja trie par date croissante, la correction
ne coute aucune requete supplementaire.

La date n'avait rien a corriger : `routed_at` est deja celle de l'attribution d'origine, la
reattribution ne le touchant pas — c'est un choix delibere de F10.

**L'alternative ecartee** : une colonne `lead.original_sales_rep_id` remplie par
`RoutedLeadWriter`. Comme `V7`, elle aurait ete nullable et sans remplissage retroactif, donc
tout lead deja attribue aurait affiche « inconnu » la ou la derivation donne la bonne reponse,
y compris pour l'historique. Plus de schema pour moins de verite. Le seul argument pour elle
— survivre a une purge de `lead_action` — ne tient pas : rien ne purge cette table.

## Les trois tests, ecrits avant le correctif

Vus rouges, puis verts. Ils vivent dans `LeadTimelineServiceTest`.

| Test | Ce qu'il verrouille |
| --- | --- |
| `sansReattributionLAttributionMontreLeTitulaireCourant` | le cas courant ne bouge pas |
| `lAttributionMontreLeTitulaireDOrigineEtNonLeCourant` | rendait « Amina » au lieu de « Karim » |
| `apresDeuxReattributionsLAttributionMontreLeToutPremierTitulaire` | rendait « Sofia », l'intermediaire etant « Amina » |

Le troisieme est celui qui compte : il distingue « la premiere reattribution » de « la
derniere », et un `findFirst` pris au mauvais bout du journal le fait echouer.

## La recette

Faite par l'utilisateur a l'ecran, sur le lead `karim.ouazzani@zenith-conseil.test` — un lead
reattribue de Karim Haddad vers Amina Bensalem pendant la recette de F10, et donc le temoin
ideal, deja present en base. La ligne « Attribution » nomme desormais **Karim Haddad**.

## L'etat de la machine

**Tout tourne encore** : `leadflow-postgres`, `leadflow-rabbitmq`, le backend sur `:8090` en
profil `dev` et le dashboard sur `:4200`. Pour eteindre : arreter les deux processus, puis
`docker compose down` sans `-v`.

Flyway a rejoue `R__demo_data.sql` au demarrage, son contenu ayant change a la tache
precedente. Sans incident : la boutique de demonstration portait deja ces valeurs.

## La suite

Rien n'est en cours. Ce que `CLAUDE.md` liste toujours comme absent, par ordre de valeur
apparente : **aucune notification n'est envoyee au commercial** — le seuil « chaud » a un
badge mais personne n'est prevenu —, **une reattribution ne remonte pas jusqu'a l'ERP**, et
**aucun graphique** n'existe dans le dashboard.
