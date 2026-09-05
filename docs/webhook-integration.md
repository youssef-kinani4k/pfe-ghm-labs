# Intégrer un formulaire à LeadFlow

Ce document décrit le contrat du webhook de capture. Il est la référence pour le snippet
posé sur le site d'un client.

## Endpoint

```
POST /api/webhooks/leads/{clePublique}
Content-Type: application/json
X-Leadflow-Signature: t=<epoch secondes>,v1=<hmac hexadecimal>
```

`clePublique` est la valeur de `client.public_key`. Elle n'est pas secrète : c'est la
signature qui authentifie. Elle peut être révoquée sans recréer le client.

## Corps

Un objet JSON libre, à une exception près : le champ `source` est obligatoire et indique le
canal d'origine chez le client.

```json
{
  "source": "formulaire-devis",
  "email": "karim@acme.test",
  "telephone": "+212600000000",
  "message": "Je veux un devis pour 50 unites"
}
```

Tout le reste est stocké tel quel et interprété plus tard par la qualification. Le
middleware ne valide ni l'email, ni le téléphone.

## Signature

```
charge    = <epoch secondes> + "." + <corps JSON exact, octet pour octet>
signature = HMAC-SHA256(secret du client, charge)  ->  hexadecimal minuscule
en-tete   = "t=" + <epoch secondes> + ",v1=" + signature
```

Le corps signé doit être **exactement** celui envoyé : un espace ajouté après signature
invalide la requête.

L'horodatage doit être à moins de 5 minutes de l'heure du serveur, dans les deux sens.

### En PHP

```php
$corps = json_encode([
    'source' => 'formulaire-devis',
    'email'  => 'karim@acme.test',
]);
$t = time();
$signature = hash_hmac('sha256', $t . '.' . $corps, $secret);

$ch = curl_init('https://leadflow.example/api/webhooks/leads/' . $clePublique);
curl_setopt_array($ch, [
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => $corps,
    CURLOPT_HTTPHEADER => [
        'Content-Type: application/json',
        "X-Leadflow-Signature: t=$t,v1=$signature",
    ],
]);
curl_exec($ch);
```

### En JavaScript (Node)

```js
import crypto from 'node:crypto';

const corps = JSON.stringify({ source: 'formulaire-devis', email: 'karim@acme.test' });
const t = Math.floor(Date.now() / 1000);
const signature = crypto.createHmac('sha256', secret).update(`${t}.${corps}`).digest('hex');

await fetch(`https://leadflow.example/api/webhooks/leads/${clePublique}`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Leadflow-Signature': `t=${t},v1=${signature}`,
  },
  body: corps,
});
```

Le secret ne doit jamais partir dans un navigateur : la signature se calcule côté serveur.

### Vérifier en local avec curl

Backend démarré sur `:8090` et données de démonstration chargées (profil `dev`).

> **Les deux valeurs ci-dessous ne sont pas fiables sur une base déjà utilisée.** Elles sont
> celles que `backend/src/main/resources/db/dev/R__demo_data.sql` pose sur une base neuve,
> mais la base de `docker compose` est persistante : dès qu'on a tourné la clé publique ou le
> secret depuis l'écran des boutiques, ce sont les valeurs tournées qui font foi, et cet
> exemple rend `401`. La migration répétable ne les restaure pas, puisqu'elle ne se rejoue
> que si son contenu change. Le secret HMAC écrit en commentaire de ce fichier, lui, **est
> bon** : `DonneesDeDemoTest` déchiffre la colonne à chaque `./mvnw test` et vérifie qu'elle
> vaut exactement la valeur documentée. On l'a cru faux entre le 31 août et le 4 septembre
> 2026 ; l'exemple ci-dessous a été rejoué le 4 septembre sur une base de développement et
> rend bien `202`.
>
> **Les valeurs qui font foi se lisent et se refont depuis le dashboard**, écran
> « Boutiques » : la fiche affiche le chemin de webhook, donc la clé publique, et le bouton
> de rotation du secret le rend **une seule fois**, à la rotation. C'est le geste à faire
> avant de rejouer cet exemple.

```bash
CLE="demo-cd253966049ebd76243248e8"          # sur base neuve ; sinon, lire la fiche boutique
SECRET="c6702b700b1673ae027ce903ff753c4239522e71b21297259c396540b9e5ec19"   # voir l'encadré
CORPS='{"source":"formulaire-devis","email":"karim@acme.test"}'
T=$(date +%s)
SIG=$(printf '%s' "$T.$CORPS" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')

curl -i -X POST "http://localhost:8090/api/webhooks/leads/$CLE" \
  -H "Content-Type: application/json" \
  -H "X-Leadflow-Signature: t=$T,v1=$SIG" \
  -d "$CORPS"
```

Attendu : `202 Accepted` et `{"eventId":"..."}`.

## Rotation du secret

Quand l'agence régénère le secret HMAC de votre boutique depuis le dashboard, **le
redéploiement de votre site n'a pas besoin d'être instantané**. Les deux secrets — l'ancien
et le nouveau — sont acceptés le temps d'une fenêtre de transition (24 heures par défaut) :
une requête signée avec l'un ou l'autre reçoit le même `202`.

Ce que cela change concrètement pour votre intégration :

- Vous pouvez continuer à signer avec l'ancien secret pendant la fenêtre, sans interruption
  de capture, le temps de déployer le nouveau.
- Vous **devez** avoir basculé sur le nouveau secret avant la fin de la fenêtre : passé ce
  délai, l'ancien cesse de signer, et une requête qui l'utilise encore reçoit `401` — la même
  réponse qu'une signature fausse, sans distinction possible.
- Si l'agence révoque l'ancien secret avant la fin de la fenêtre (par exemple après une
  fuite), le même effet est immédiat : basculez sur le nouveau secret dès que possible.

La fenêtre ne change rien au format de la signature ni à la tolérance sur l'horodatage,
décrits plus haut — seul le secret utilisé pour la calculer peut, temporairement, être l'un
des deux.

## Réponses

| Code | Signification |
| --- | --- |
| `202` | Accepté. Le corps porte `eventId`. |
| `401` | Authentification refusée. La cause exacte n'est pas divulguée. |
| `400` | Corps illisible, ou champ `source` absent. |
| `413` | Corps au-delà de 64 Ko. |

## Rejeu

Renvoyer **exactement** la même requête (même corps, même en-tête) ne crée pas de second
lead : le service rend le `eventId` déjà attribué avec un `202`. Un client peut donc
réessayer sans risque après un timeout réseau.
