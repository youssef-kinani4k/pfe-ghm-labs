# Etat de session — l'alerte au commercial part vers un vrai relais

Date : 2026-09-11. Branche : `fix/f12-smtp-reel-starttls`, fusionnee dans `main` en `--no-ff`
(`1fe7a11` puis `83f0867`). Conservee, comme toutes les branches de ce depot.

## Le besoin

La demonstration de soutenance doit montrer un vrai e-mail qui arrive dans une vraie boite,
et non le message intercepte par Mailpit comme pendant la recette de F12. Le compte Gmail de
l'etudiant sert de relais, avec un mot de passe d'application — qui ne doit jamais entrer
dans le depot.

## Ce qui bloquait

`CanalSmtp` n'activait jamais STARTTLS. Gmail, comme tout relais transactionnel, refuse
l'authentification sur une connexion en clair (« 530 Must issue a STARTTLS command first ») :
aucune alerte n'aurait pu partir, et l'identifiant etait propose en clair au serveur. Le test
ecrit en premier, `unIdentifiantNePartJamaisSurUneConnexionEnClair`, a reproduit exactement
cela contre GreenMail — l'envoi partait et c'est le serveur qui refusait les identifiants.

## Ce qui a ete fait

- **STARTTLS actif des que le relais le propose**, et **exige des qu'un identifiant est
  fourni** : un relais authentifie sans chiffrement fait echouer l'envoi plutot que de
  recevoir le mot de passe lisible. Mailpit, sans identifiant, reste en clair, donc la
  simulation locale ne change pas.
- **Les secrets du poste sortent du depot** : `backend/.env`, ignore par git (motif `.env*`,
  verifie par `git check-ignore`), lu par le **seul profil `dev`** via `spring.config.import:
  optional:file:.env[.properties]`. Le modele versionne est `backend/.env.example`, et
  `.env.prod.example` gagne le meme bloc pour la production. Une vraie variable
  d'environnement l'emporte toujours sur le fichier, et sans fichier le profil `dev` demarre
  comme avant.
- L'import a ete verifie par un test jetable — contexte Spring minimal en profil `dev`, faux
  `.env` — supprime ensuite avec son fichier. Aucun test du depot n'active le profil `dev`.
- `CLAUDE.md` porte desormais ces deux invariants dans la section « Notification ».

`./mvnw verify` : **599 tests, 0 echec, BUILD SUCCESS**. La CI n'est pas affectee — le job
`fumee` fabrique son propre `.env.prod` sans aucune variable SMTP, donc le canal y reste
inerte.

Un premier passage de `verify` avait echoue : la VM Docker est tombee en plein milieu (des
dizaines de conteneurs en `Exited (255)` a la meme minute). Cause d'environnement et non de
code — la suite leve une paire Postgres + RabbitMQ par contexte Spring, soit 31 conteneurs et
~2,6 Go sur les 3,8 Go de la VM. Relancee apres nettoyage, elle est verte.

## Ce qui reste a faire, et qui appartient a l'utilisateur

1. Remplir `backend/.env` : adresse Gmail, mot de passe d'application (16 lettres **sans
   espaces**, la validation en deux etapes doit etre active), et la meme adresse en
   expediteur.
2. `docker compose up -d` puis `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`.
3. Ecran « Parametres » : le test d'envoi vers sa propre adresse. Il envoie un vrai message
   sans passer par un lead, et n'ecrit aucune ligne de `notification_attempt`.
4. Ecran « Boutiques » : mettre une vraie adresse sur un commercial a la place de
   `@demo.test`, sans quoi aucune alerte de lead n'arrivera nulle part.
5. Apres la soutenance : revoquer le mot de passe d'application dans le compte Google.

## Ce qui n'a pas ete fait, volontairement

- **Aucun envoi reel n'a ete eprouve** : le mot de passe appartient a l'utilisateur, et
  l'etage d'integration `CanalSmtpIntegrationTest` (`-Pnotification-it`) reste le bon endroit
  pour cette preuve, avec `LEADFLOW_SMTP_DESTINATAIRE_DE_TEST`.
- Rien n'a change dans la deliverabilite : un message parti d'une adresse Gmail personnelle
  peut arriver en indesirables. Une vraie agence prendrait un relais transactionnel et son
  propre domaine, sans une ligne de code a changer — seulement les cinq variables.
