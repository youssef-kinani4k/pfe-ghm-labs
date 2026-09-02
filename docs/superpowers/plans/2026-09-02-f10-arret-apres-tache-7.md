# F10 — etat du depot a l'arret : sept taches sur dix

Session du 2 septembre 2026, arretee a la demande de l'utilisateur apres la tache 7. La
feature est **a moitie livree** : tout le backend est fait, relu et vert ; **rien du frontend
n'est commence**, et c'est ce qui rend la branche non fusionnable en l'etat.

## L'etat du depot

| | |
| --- | --- |
| Branche courante | `feature/f10-reattribution-manuelle` |
| Dernier commit | `9163f7d` |
| Arbre de travail | **propre** |
| Pousse ? | **non** — la branche n'existe que localement |
| Migrations | **huit** — `V8__lead_action.sql` est la derniere |
| Suite backend | **449/449** au dernier passage complet (tache 6) ; 177/177 sur `capture` + `routing` + `monitoring` dans un seul JVM (tache 7) |
| Frontend | **intact, et desormais en retard sur le backend** — voir « Ce qui casse » |

## Ce que la session a produit

Le brainstorming, la spec, le plan, puis sept taches executees en subagent-driven
development — un implementeur par tache, une relecture par tache, ledger tenu dans
`.superpowers/sdd/2026-09-02-f10-reattribution-manuelle/progress.md`.

| Commit | Tache |
| --- | --- |
| `70af86c`, `475e405` | le design de F10 |
| `a498ff0` | le plan d'implementation, en dix taches |
| `b66bdd4` | T1 — la table `lead_action`, ses enumerations, l'entite, le depot, **et l'index unique partiel de `dead_letter`** (la dette de F6, payee) |
| `3983281` | T2 — `LeadActionJournal`, et le rattrapage du doublon de mort dans le listener |
| `b4e3b80` | T3 — `RoutedLeadWriter.reattribue`, qui ne touche ni le statut ni `routed_at` |
| `f5b4604` | T4 — `ReattributionService` et ses quatre refus |
| `89dad9c` | T5 — `POST /api/leads/{id}/reassign`, son `409`, son `404`, son `400` |
| `83c2cf3` | T6 — le motif obligatoire sur le rejeu et l'ecart, journalises avec leur issue |
| `9163f7d` | T7 — la chronologie montre les gestes humains, sans les compter deux fois |

Les sept relectures sont revenues **approuvees**, sans aucun defaut Critical ni Important
attribuable a la tache relue.

## Ce qui casse tant que les taches 8 a 10 ne sont pas faites

C'est le point a retenir avant de relancer quoi que ce soit.

**Le journal des morts est casse a l'ecran.** Depuis T6, `POST /api/dead-letters/{id}/replay`
et `/discard` exigent un corps `{"reason": "…"}` valide. Le frontend n'en envoie pas : les
boutons « Rejouer » et « Ecarter » rendent donc `400`. C'etait une decision prise en connaissance
de cause au demarrage — la branche n'est fusionnee qu'apres la tache 10 — mais elle veut dire
qu'une recette a l'ecran faite maintenant montrerait un ecran degrade.

**La chronologie affichera deux faits sans libelle.** `TimelineEventType` porte desormais huit
valeurs cote backend ; l'union TypeScript de `frontend/src/app/core/models/lead.ts` en connait
six, et la table `FAITS` de `lead-timeline.ts` est exhaustive sur cette union. Une entree
`REATTRIBUTION` ou `ECART` sortira donc sans libelle ni icone.

**Aucun ecran ne permet encore de reattribuer.** L'endpoint existe, le bouton non.

Autrement dit : **ne pas fusionner dans `main`, ne pas recetter a l'ecran** avant les taches
8 et 9.

## Les deux decisions que j'ai tranchees seules

**L'ecart d'un message mort est un fait distinct du rejeu.** Le plan repliait
`LeadActionType.ECART` sur `TimelineEventType.REJEU` au motif que c'est « la meme decision
humaine ». Refuse : l'ecran aurait affiche « Rejeu » sur un message definitivement abandonne,
ce qui est faux. `TimelineEventType` a donc gagne **deux** valeurs — `REATTRIBUTION` apres
`ATTRIBUTION`, `ECART` apres `REJEU` — et `typeDe(...)` est un `switch` d'expression sans
branche par defaut, pour qu'une valeur ajoutee plus tard ne compile pas en silence. Cout si
c'est faux : une valeur de plus dans un contrat que le frontend traite deja par table de
correspondance.

**La rupture temporaire du contrat de rejeu est acceptee.** Decouper autrement aurait voulu
dire rendre le motif facultatif en T6 puis le durcir en T9 — deux fois le meme travail. Cout
si c'est faux : l'ecran degrade decrit plus haut, sans consequence sur le code livre.

## Par quoi reprendre

Les trois taches restantes sont ecrites en detail, avec leur code, dans
`docs/superpowers/plans/2026-09-02-f10-reattribution-manuelle.md`.

**Tache 8 — l'ecran de reattribution.** Modeles, `LeadApi.reattribue`, le dialogue
`ReattributionDialog` (liste des commerciaux actifs de la boutique, motif obligatoire,
avertissement si le lead est `SYNCED`), son cablage dans la fiche du lead avec
`leadTimeline.recharge()`, et les libelles de la chronologie. **Amendement au plan** : la
table `FAITS` et l'union `TimelineEventType` doivent recevoir **deux** valeurs, pas une —
`REATTRIBUTION` et `ECART`. La tache commence par invoquer les skills du plugin
`ui-ux-pro-max`, avant d'ecrire le moindre template.

**Tache 9 — le motif a l'ecran du journal des morts.** Un `MotifDialog` partage dans
`shared/`, en remplacement des `confirm()` natifs, et le motif porte par
`DeadLetterApi.rejoue`/`.ecarte`. Le rejeu de selection demande **un seul motif pour tout le
lot**.

**Tache 10 — documentation et verification complete.** `CLAUDE.md` (huit migrations, la
reattribution dans la section Routage, le manque retire de « Etat actuel »),
`docs/monitoring-api.md`, puis `./mvnw verify` et la triade frontend, puis le push.

Pour reprendre l'execution en subagent-driven development, le ledger et les briefs sont
intacts dans `.superpowers/sdd/2026-09-02-f10-reattribution-manuelle/` : les taches portant
une ligne `complete` ne doivent pas etre redispatchees.

## Les dettes reportees, relevees par les relectures

Aucune n'est bloquante ; elles sont a trier par la relecture finale de branche.

- Le chemin `ECHEC` du rejeu — la ligne de journal ecrite avant que l'exception ne parte —
  n'a pas de test. Correct par inspection (`LeadActionJournal` est en `REQUIRES_NEW`, donc la
  ligne survit au rollback de la transaction englobante), mais sans garde-fou.
- Les messages de refus de la reattribution embarquent l'UUID brut du commercial ; a arbitrer
  cote interface.
- Le log du doublon de mort n'indique ni la file ni la cle de routage.
- Fixtures heterogenes dans `DeadLetterReplayServiceTest`, et un fixture partage dans
  `LeadTimelineServiceTest` que la plupart des tests n'utilisent pas.

## L'etat de cette machine a l'arret

Le **demon Docker tourne** : il etait arrete au demarrage de la session et a ete relance,
Testcontainers en dependant. Aucun conteneur `docker compose` n'a ete monte ; les conteneurs
Testcontainers se retirent seuls. Rien n'ecoute sur `:8090` ni sur `:4200`.

Rappel inchange : pour arreter la pile de developpement, `docker compose down`, **jamais
`down -v`** — le volume porte l'historique de demonstration de toutes les sessions.

Et la dette de F7.2 est toujours ouverte : **le secret HMAC en clair de `R__demo_data.sql`
est faux**. Elle demande une decision — soit l'utilisateur produit la valeur chiffree, soit on
retire la valeur en clair et la rotation par l'ecran « Boutiques » devient le geste normal. En
attendant, tourner le secret depuis cet ecran apres chaque rejeu de la migration de
demonstration.
