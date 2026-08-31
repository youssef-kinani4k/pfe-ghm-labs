# Quand payer les dettes — le plan de règlement

Écrit le 31 août 2026, après la fusion de F11.3. Ce document ne recense pas les dettes
(l'inventaire vit dans `2026-08-29-feuille-de-route-f8-f14-design.md` et dans la section
« Ce qui n'existe pas » de `CLAUDE.md`) : il dit **quand** payer chacune, et pourquoi à ce
moment-là.

**La règle qui gouverne tout le document :** une dette se paie dans la session qui ouvre déjà
ce fichier. La payer isolément coûte un contexte à recharger ; la payer plus tard coûte plus
cher qu'aujourd'hui.

## Maintenant — une demi-session, avant d'ouvrir F9

Trois choses, et la même raison pour les trois : elles grossissent si on attend.

**1. ~~Les 83 fichiers Prettier~~ et les 2 constats ESLint. Payé le 31 août.** Le compte de
83 était faux : relevé sur un poste Windows en CRLF, quand Prettier compare avec
`endOfLine: lf`. La CI, qui extrait en LF, n'en voyait que **11**. `* text=auto eol=lf` dans
`.gitattributes` aligne désormais les deux mesures. Les 11 fichiers sont formatés, les 2
constats corrigés, et le job `qualite` a perdu ses deux `continue-on-error` : il bloque.

Ce que ce paragraphe disait avant d'être payé — l'argument du « maintenant » reste juste,
c'est seulement le chiffre qui était faux : le plus urgent, et le seul qui soit
réellement chronométré. F9, F10 et F12 vont créer des écrans ; chaque écran écrit avant le
reformatage est un fichier de plus à reformater, et un diff futur pollué. Fait maintenant,
c'est **un commit isolé, mécanique, relisible d'un coup d'œil** — `prettier --write .`, plus
deux lignes pour `no-empty-function` dans `auth.spec.ts:68` et `no-autofocus` dans
`login.html:16`. Ensuite `qualite` devient bloquant : retirer le `continue-on-error` **du
job** dans `.github/workflows/ci.yml` ; celui du pas ESLint peut partir en même temps. Fait
après F12, c'est environ 130 fichiers et un diff que personne ne relit.

**2. ~~La protection de branche.~~ Corrigé le 31 août : elle n'est pas configurable.** Le
dépôt est privé sur un compte gratuit, et l'API GitHub rend `403 — Upgrade to GitHub Pro or
make this repository public` sur les règles de protection **comme** sur les rulesets. Les
« deux minutes dans *Settings → Branches* » annoncées ici n'existaient pas ; cela ne pouvait
se découvrir qu'en essayant.

Un crochet `pre-push` versionné (`.githooks/pre-push`) tient ce rôle, et le tient mieux :
les fusions de ce projet étant locales et en `--no-ff`, une protection côté serveur n'aurait
parlé qu'après que `main` a bougé. Il reste deux façons d'obtenir la vraie protection —
passer le dépôt en public, ou payer GitHub Pro — et aucune n'est requise pour la soutenance.
Voir `docs/integration-continue.md`.

**3. La recette de F8.** Due depuis quatre sessions, cinq minutes, et le seul point de la
liste que l'assistant ne peut pas faire : baisser le seuil d'une boutique, recharger la
liste, et voir les pastilles « chaud » se déplacer **sans qu'aucun score ne change**. Plus
elle attend, plus le risque est qu'elle se découvre cassée la veille de la soutenance.

## Dans la feature qui touche déjà le code

À ne pas sortir en session dédiée : chacune est à quelques lignes du chemin de sa feature.

| Dette | À payer dans | Pourquoi là |
| --- | --- | --- |
| `dead_letter` sans contrainte d'unicité | **F10** | F10 écrit déjà la migration `V7` et ouvre le journal d'actions ; un index unique de plus y coûte trois lignes |
| `DolibarrConnector` ne cherche pas de tiers existant par email | **F14** | F14 refait le pivot ERP et rouvre `crm/` de toute façon |
| Secret HMAC de la démo, faux dans `R__demo_data.sql` depuis F7.2 | **F9** | Première session à remonter une base de démo ; corriger la valeur documentée pendant qu'on y est |

## Jamais — à assumer, pas à réparer

**Le mono-instance à quatre endroits** (`PendingEventRelay`, le tour de rôle de F4, le
registre d'émetteurs SSE de `LeadStreamBroadcaster`, le limiteur de débit de F11.2) et
**l'absence de notification au commercial**.

Ce ne sont pas des oublis mais des décisions écrites, et les réparer coûterait plus que ce
qu'elles rapportent : un compteur partagé, un `SELECT ... FOR UPDATE SKIP LOCKED`, un bus SSE
entre instances — pour un produit d'agence qui tourne sur une machine. **Le bon moment pour
les traiter est la soutenance, à l'oral**, en montrant qu'on sait exactement ce qu'il
faudrait faire et pourquoi on ne l'a pas fait. Un jury sanctionne une limite ignorée, pas une
limite nommée avec son remède.

Même chose pour **le TLS et le nonce CSP** : ils n'ont de sens que le jour où la pile est
réellement exposée sur un domaine. Tant qu'elle tourne sur `localhost:8088`, les implémenter
serait du théâtre.

## La recommandation en une phrase

**La prochaine session commence par la demi-session de nettoyage, puis enchaîne sur F9** — les
deux tiennent ensemble, et les quatre features restantes s'abordent alors sur une base propre,
avec une CI qui bloque vraiment.
