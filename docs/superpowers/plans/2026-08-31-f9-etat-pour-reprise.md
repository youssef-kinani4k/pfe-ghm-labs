# État du dépôt à l'arrêt — session F8/F9 du 31 août 2026

Troisième session du 31 août. La première a livré F11.3, la deuxième le nettoyage lint et
format. Celle-ci a **éteint la dette la plus ancienne du projet** — la recette de F8, due
depuis cinq sessions — et **conçu puis planifié F9**, sans en écrire une ligne de code.

## L'état du dépôt

| | |
| --- | --- |
| Branche courante | `feature/f9-timeline-du-lead` |
| Dernier commit | `aff675b` (le plan d'implémentation de F9) |
| Arbre de travail | **propre** |
| Poussé ? | **oui, tout** — la branche suit `origin/feature/f9-timeline-du-lead` |
| Commits de la session | trois : `ee8f397`, `eb4e998`, `aff675b` |
| `main` | inchangé, à `4a7d745` |
| CI | déclenchée par la poussée (`fumee` écoute `feature/**`) — **résultat non vérifié** |

## Ce que la session a livré

### 1. La recette de F8 est passée

Le seul point qui restait ouvert, et le seul que l'assistant ne pouvait pas faire.

Six leads de démonstration ont été fabriqués **en passant par le webhook signé**, donc par
tout le pipeline, avec des scores étagés autour du seuil : 80, 65, 65, 55, 45, 10. Les
scores sont tombés exactement là où le barème par défaut les prédit, ce qui éprouve le
scoring au passage.

L'utilisateur a baissé le seuil chaud depuis l'écran « Barème », vu les pastilles se
déplacer, puis remonté le seuil. **Vérifié après coup : les six scores sont identiques à ce
qu'ils étaient à la qualification.** Le badge bouge, le score non — c'est exactement ce que
F8 promettait.

### 2. Trois pièges de l'environnement, documentés (`ee8f397`)

Les trois ont coûté une heure de détour, et aucun ne se voit en lisant le code.

**`spring-boot:test-run` ne convient pas pour faire tourner le produit.**
`TestBackendApplication` vit dans `src/test`, donc le lancement embarque tout le classpath
de test. Deux conséquences, et la seconde est la pire :

- il démarre en profil **`default`**, donc sans repli pour le compte opérateur — **toute
  connexion échoue par « Identifiants invalides »** — et sans `R__demo_data.sql` ;
- `src/test/resources/application.properties` **éteint les cinq consommateurs**, donc le
  pipeline est inerte : le webhook rend `202` et le dashboard reste à zéro.

Le contrôle qui tranche en une commande, une fois le backend levé :

```bash
docker exec leadflow-rabbitmq rabbitmqctl list_queues name messages consumers
```

Cinq files avec un consommateur chacune = pipeline vivant. Aucune file = consommateurs
éteints.

**L'exemple `curl` de `docs/webhook-integration.md` portait des valeurs périmées**, et
**le secret HMAC en clair de `R__demo_data.sql` est confirmé faux** — `401` sur base neuve.

### 3. F9 conçue et planifiée

- **Spec** : `docs/superpowers/specs/2026-08-31-f9-timeline-du-lead-design.md` (`eb4e998`)
- **Plan** : `docs/superpowers/plans/2026-08-31-f9-timeline-du-lead.md` (`aff675b`), huit
  tâches avec leur code, leurs tests et leurs commits.

**Aucune ligne de code de F9 n'est écrite.** L'implémentation attend le « vas-y ».

## Le piège qui attend la prochaine session

**Au prochain démarrage du backend, Flyway rejouera `R__demo_data.sql`.** La migration est
répétable et son contenu a changé dans `ee8f397`, donc son empreinte aussi.

Conséquence concrète : la boutique de démonstration **retrouvera la clé publique documentée
et le secret HMAC cassé**. Les leads fabriqués aujourd'hui restent en base — le `ON CONFLICT`
ne touche que `client` et `sales_rep` — mais **envoyer un nouveau lead échouera en `401`**
tant que le secret n'aura pas été refait tourner.

Le geste, depuis l'interface et non en base : écran **Boutiques** → fiche de la boutique de
démonstration → rotation du secret. Il n'est rendu **qu'une fois**, à la rotation.

## Deux décisions de F9 à ne pas perdre

**F9 porte une migration, contre ce qu'annonçait la feuille de route.** L'inventaire des
horodatages a montré que l'attribution au commercial n'est datée nulle part, et que
`updated_at` ne la remplace pas — il vaut la date d'attribution pour un lead resté `ROUTED`
et celle de la synchronisation pour un lead `SYNCED`. `V7` ajoute `lead.routed_at`, nullable
et **sans remplissage rétroactif**.

**F10 prendra donc `V8`**, et son index unique sur `dead_letter` avec. La feuille de route
annonce encore `V7` pour F10 : c'est la **deuxième** fois que ce numéro glisse, `V6` ayant
déjà été consommée par F11.2.

## L'état de cette machine à l'arrêt

**Deux conteneurs tournent encore** — je ne les ai pas arrêtés, la décision vous revient :

```
leadflow-postgres   Up (healthy)
leadflow-rabbitmq   Up (healthy)
```

Pour les arrêter : `docker compose down` depuis la racine. **Ne pas faire `down -v`** : le
volume porte l'historique de démonstration de toutes les sessions — trois boutiques, les
leads des recettes F6, F7 et F8.

- **Le backend et le dashboard tournaient** (`:8090` et `:4200`), lancés depuis cette
  session ; ils s'arrêtent en fermant leurs processus. Les tâches d'arrière-plan de la
  session meurent avec elle, mais **le JVM enfant de `mvnw` survit à l'arrêt de son parent**
  et garde le port — c'est arrivé deux fois aujourd'hui. Le vérifier avant de relancer.
- **L'assistant n'a pas la permission d'arrêter un processus** sur ce poste : le
  classificateur refuse `Stop-Process` comme `taskkill`. C'est à vous de le faire, et sous
  Git Bash il faut doubler les slashs : `taskkill //PID <pid> //F`.
- **Docker Desktop est démarré.**

## Par quoi reprendre

**Exécuter le plan de F9**, `docs/superpowers/plans/2026-08-31-f9-timeline-du-lead.md`, tâche
par tâche. La branche existe et est à jour ; il n'y a rien à préparer côté git.

Pour monter la pile — et **jamais `spring-boot:test-run`**, voir plus haut :

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm start
```

Puis, avant d'envoyer le moindre lead : refaire tourner le secret de la boutique de
démonstration depuis l'écran « Boutiques ».

L'ordre des features restantes est inchangé : **F9 → F10 → F12 → F13 → F14**, quatre à six
sessions après F9.

## Les dettes, remises à jour

**Payées cette session** : la recette de F8, et le signalement des trois pièges
d'environnement.

**Toujours ouverte, et déplacée** : la correction du secret HMAC de démonstration. Elle était
prévue « dans F9 » ; le mensonge est désormais signalé aux trois endroits, mais **la
correction elle-même reste due** — elle demande de rechiffrer une valeur avec la clé maître
de développement, opération que le classificateur a refusée à l'assistant. À trancher : soit
l'utilisateur produit la valeur, soit on assume la rotation par l'interface comme geste
normal et on retire la valeur en clair du fichier.

**Dans la feature qui touche déjà le code**, inchangé : l'index unique de `dead_letter` avec
la migration **`V8`** de F10 ; la recherche de tiers Dolibarr par email avec F14.

**Jamais**, et à défendre à l'oral : le mono-instance à quatre endroits, l'absence de
notification au commercial, le TLS et le nonce CSP.
