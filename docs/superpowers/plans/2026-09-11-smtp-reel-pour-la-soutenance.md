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

## La demonstration a ete jouee de bout en bout

Le `.env` rempli par l'utilisateur, la chaine entiere a ete eprouvee sur le poste, a la
demande de l'utilisateur et non par lui.

1. **Sonde de l'ecran « Parametres »** : `ok: true`, « Message d'essai accepte par le
   relais ». Gmail a donc accepte l'authentification — la preuve que STARTTLS repare bien ce
   qui bloquait — et le mot de passe venait de `backend/.env`.
2. **Lead reel signe en HMAC sur le webhook** : `202`, puis le pipeline sans intervention —
   qualification (intention `DEVIS` par **Gemini**, score **80**), attribution a Amina
   Bensalem par tour de role, synchronisation **Dolibarr en SUCCES**, et
   **notification `ENVOYEE` par SMTP** a l'adresse reelle, seuil 70 contre score 80. Le mail
   est arrive dans la boite.

Trois pieges rencontres, qui coutent du temps a qui les refait :

- **PowerShell 5.1 calcule `Get-Date -UFormat %s` en heure locale**, pas en UTC. Le premier
  webhook a rendu `401` avec « Horodatage hors fenetre : ecart de 3599s ». Signer demande
  `[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()`.
- **Le secret de `R__demo_data.sql` ne vaut plus** : une rotation faite depuis le dashboard
  lors d'une session anterieure l'avait remplace, et cette migration repetable ne se rejoue
  pas. Il a fallu tourner le secret depuis l'API d'administration — le geste prevu quand un
  secret est perdu — ce qui le rend en clair une fois. Tout script signant avec la valeur du
  fichier recevra `401` des la fenetre de transition passee.
- **Deux commerciaux d'une meme boutique ne peuvent pas partager une adresse**
  (`uq_sales_rep_client_email`, un `409`). Les deux commerciaux de demonstration pointent
  desormais vers la meme boite : l'adresse reelle pour Amina, un alias `+karim` pour Karim,
  afin que l'alerte arrive quel que soit le titulaire choisi par le tour de role.

Un premier lead avait ete alerte vers `karim@demo.test`, adresse fictive que Gmail accepte a
la remise avant de la rejeter : le rapport de non-distribution qui revient est normal, et
non un echec du canal — la trace, elle, dit bien `ENVOYEE`.

## Ce qui reste a faire

- Apres la soutenance : revoquer le mot de passe d'application dans le compte Google.
- Avant une repetition sur une base neuve (`docker compose down -v`), refaire les deux
  reglages : les adresses reelles sur les commerciaux, et relever le secret courant de la
  boutique depuis l'ecran « Boutiques ».

## Ce qui n'a pas ete fait, volontairement

- **L'etage d'integration n'a pas ete joue** : la preuve est venue du produit lui-meme, sonde
  puis lead reel. `CanalSmtpIntegrationTest` (`-Pnotification-it`,
  `LEADFLOW_SMTP_DESTINATAIRE_DE_TEST`) reste le bon endroit pour rejouer cette preuve depuis
  la suite, sans dashboard ni broker.
- Rien n'a change dans la deliverabilite : un message parti d'une adresse Gmail personnelle
  peut arriver en indesirables. Une vraie agence prendrait un relais transactionnel et son
  propre domaine, sans une ligne de code a changer — seulement les cinq variables.
