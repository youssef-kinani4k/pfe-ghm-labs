# Accueillir une boutique

Ce document décrit, de bout en bout, l'accueil d'une nouvelle boutique sur LeadFlow. Il est
écrit pour la personne qui gère les clients de l'agence, **pas** pour un développeur :
aucune commande, aucune base de données, aucun fichier de configuration. Tout se fait depuis
le dashboard.

Avant F7, ajouter une boutique demandait une insertion SQL précédée du chiffrement manuel de
deux champs. Ce n'est plus le cas.

---

## Ce qu'il faut avoir sous la main avant de commencer

| Information                        | Qui la fournit                          |
| ---------------------------------- | --------------------------------------- |
| Nom de la boutique                 | vous                                    |
| Adresse de son ERP (Dolibarr/Odoo) | la boutique, ou celui qui l'a installé  |
| Clé d'API de l'ERP                 | la boutique — c'est un secret, à traiter comme tel |
| Nom et email du premier commercial | la boutique                             |

Sans clé d'API valide, la création n'aboutira pas : l'écran refuse de créer une boutique
dont la connexion ERP n'a pas été testée avec succès. Ce n'est pas une lourdeur — une
boutique créée avec une mauvaise clé ne le découvrirait qu'au premier lead perdu.

---

## 1. Se connecter

Ouvrir le dashboard, saisir l'identifiant et le mot de passe de l'opérateur. Un seul compte
existe pour toute l'agence : le dashboard est une console interne, pas un espace client.

## 2. Créer la boutique

**Boutiques** dans le menu de gauche, puis **Nouvelle boutique**. Trois sections :

**Identité.** Le nom, et la règle d'attribution des leads :

| Règle          | Ce qu'elle fait                                                            |
| -------------- | -------------------------------------------------------------------------- |
| Tour de rôle   | chaque lead va au commercial servi le plus anciennement                     |
| Géographique   | filtre sur la zone du prospect, puis tour de rôle si personne ne correspond |
| Sectorielle    | filtre sur le secteur d'activité, puis tour de rôle de la même façon        |

Les deux dernières **retombent toujours** sur le tour de rôle plutôt que de laisser un lead
sans destinataire. La règle se change ensuite depuis la fiche, à tout moment.

**ERP.** Choisir le fournisseur ; les champs à remplir apparaissent d'eux-mêmes — ce sont
ceux que le connecteur déclare, et ils diffèrent d'un ERP à l'autre. Puis **Tester la
connexion**. Le résultat est écrit en français :

| Message                                             | Ce qu'il faut corriger                        |
| --------------------------------------------------- | --------------------------------------------- |
| Connexion établie.                                   | rien, on peut continuer                       |
| Aucun serveur ne répond à cette adresse.             | l'adresse, ou l'ERP est éteint                |
| Le serveur répond mais refuse la clé ou le compte.   | la clé d'API, ou le compte de service         |
| Le serveur répond mais ne connaît pas cette base.    | le nom de la base (Odoo)                      |
| Réponse illisible : cette adresse pointe ailleurs.   | l'adresse — souvent un chemin en trop ou manquant |

Le détail technique est replié sous « Detail technique », à transmettre tel quel à
l'informaticien de la boutique s'il faut creuser.

**Premier commercial.** Nom et email suffisent. Secteur et zone ne servent qu'aux règles
d'attribution correspondantes. Laisser la référence ERP vide : le connecteur la retrouve
seul au premier lead et la mémorise.

Le bouton **Créer la boutique** reste inactif tant que la connexion n'a pas été testée avec
succès et que le commercial n'est pas saisi ; la raison est écrite à côté du bouton. Modifier
un champ ERP après un test réussi redemande un test : un test ancien ne dit rien des
nouvelles valeurs.

## 3. Transmettre le secret — l'étape à ne pas rater

Après création, un encadré affiche **une seule fois** :

- le **secret HMAC** de la boutique,
- sa **clé publique**,
- l'**URL de son webhook**.

Ce secret n'est stocké que chiffré : personne, pas même l'agence, ne peut le réafficher
ensuite. Le transmettre à la boutique **maintenant**, par un canal sûr, puis cliquer sur
« J'ai transmis ces informations ».

Ce que la boutique doit faire de son côté : envoyer chaque soumission de formulaire en
`POST` sur l'URL du webhook, signée avec le secret. Le mode d'emploi technique à leur
transmettre est `docs/webhook-integration.md` — il contient des exemples PHP, JavaScript et
curl prêts à copier.

## 4. Vérifier le premier lead

Demander à la boutique de soumettre un formulaire de test, puis, sur le dashboard :

1. **Dashboard** — le lead apparaît dans le flux temps réel en quelques secondes.
2. **Leads** — il porte le nom de la boutique, un score, une intention et un commercial.
3. Ouvrir sa fiche — l'historique montre la synchronisation vers l'ERP en `SYNCED`.

Si rien n'arrive, regarder **File d'attente** : c'est là que se lisent les leads en échec,
avec le motif. Un lead qui n'apparaît nulle part n'a jamais été reçu — la signature ou l'URL
côté boutique est en cause, et le webhook a répondu `401`.

---

## Gérer une boutique existante

Tout se passe sur sa fiche, atteinte en cliquant sa ligne dans **Boutiques**.

**Ajouter ou retirer un commercial.** Le formulaire en bas du bloc Commerciaux ajoute ;
l'icône en bout de ligne désactive. Un commercial désactivé ne reçoit plus de leads mais
garde les siens. **Le dernier commercial actif ne peut pas être désactivé** : sans lui, les
leads de la boutique n'auraient plus de destinataire et finiraient en erreur. Il faut en
activer un autre d'abord.

**Changer les réglages ERP.** Les champs non secrets se réaffichent ; les champs secrets
partent vides, et **vide veut dire inchangé**. Ne retaper la clé d'API que pour la remplacer.
Attention : le test de connexion, lui, n'a pas accès à la clé enregistrée — pour tester
réellement, il faut la saisir. L'écran le rappelle.

**Désactiver une boutique.** Le formulaire de la boutique reçoit immédiatement une erreur
d'authentification : plus aucun lead n'entre. Les leads déjà reçus ne sont pas touchés, et la
réactivation restitue tout en l'état, commerciaux compris. La suppression n'existe pas, et
c'est délibéré : effacer une boutique effacerait l'historique de ses leads.

---

## Quand une boutique perd son secret

C'est le cas le plus fréquent, et il n'a qu'une issue : **régénérer**. Le secret n'est nulle
part en clair, ni chez nous ni dans une sauvegarde.

1. Prévenir la boutique **avant** : entre la régénération et sa mise à jour de son côté,
   son formulaire ne fonctionnera plus et les leads soumis seront refusés.
2. Fiche de la boutique → bloc Intégration → **Régénérer le secret**. La confirmation
   annonce cette conséquence.
3. Transmettre le nouveau secret depuis l'encadré — il ne s'affichera pas deux fois.
4. Faire soumettre un formulaire de test pour vérifier que la capture est rétablie.

**Régénérer la clé publique** est un geste différent : il change l'**URL** du webhook, pas
le secret. Utile si l'URL a fuité et reçoit du bruit. L'ancienne URL cesse d'être reconnue
dès la régénération, il faut donc transmettre la nouvelle dans la foulée.

Ni l'une ni l'autre de ces rotations ne touche aux leads déjà reçus.
