"""Rapatrie depuis Google Fonts les seuls sous-ensembles dont le dashboard a besoin.

Le deploiement de production ne publie qu'un port et n'appelle personne dehors ; la CSP
dit `font-src 'self'`. Les polices doivent donc etre servies par Nginx comme le reste du
bundle. Ce script n'est pas joue au build : il se relance a la main quand une graisse ou
une famille change, et son resultat est versionne.

    python build-fonts.py

Il ne garde que `latin` et `latin-ext` — l'interface est en francais, les autres
sous-ensembles (cyrillique, grec) pesaient sans jamais servir. Les familles variables
(Roboto, Fira Code) tiennent toutes leurs graisses dans un seul fichier : le script le
detecte par empreinte et n'ecrit ce fichier qu'une fois, sous une plage `font-weight`.
"""

import hashlib
import os
import re
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
DOSSIER = "src/fonts"
FEUILLE = "src/fonts.css"
SOUS_ENSEMBLES = {"latin", "latin-ext"}

CLASSE_ICONES = """
.material-symbols-outlined {
  font-family: 'Material Symbols Outlined';
  font-weight: normal;
  font-style: normal;
  font-size: 24px;
  line-height: 1;
  letter-spacing: normal;
  text-transform: none;
  display: inline-block;
  white-space: nowrap;
  word-wrap: normal;
  direction: ltr;
  -webkit-font-feature-settings: 'liga';
  -webkit-font-smoothing: antialiased;
}
"""

FAMILLES = [
    ("fira-sans", "Fira+Sans:wght@500;600;700"),
    ("roboto", "Roboto:wght@400;500;700"),
    ("fira-code", "Fira+Code:wght@400;500"),
    # La police d'icones : `mat-icon` ecrit le nom de l'icone en texte et laisse la
    # ligature y substituer le glyphe. Sans elle, aucune icone ne s'affiche.
    ("material-symbols-outlined", "Material+Symbols+Outlined"),
]


def telecharge(url):
    requete = urllib.request.Request(url, headers={"User-Agent": UA})
    return urllib.request.urlopen(requete, timeout=60).read()


os.makedirs(DOSSIER, exist_ok=True)
faces = []

for slug, spec in FAMILLES:
    css = telecharge(
        "https://fonts.googleapis.com/css2?family=%s&display=swap" % spec
    ).decode("utf-8")
    # Google prefixe chaque bloc d'un commentaire nommant le sous-ensemble.
    blocs = re.finditer(r"/\*\s*([\w\-\[\]]+)\s*\*/\s*(@font-face\s*\{.*?\})", css, re.S)
    # empreinte du fichier -> face deja ecrite, pour ne pas versionner deux fois la meme
    # police variable sous deux graisses.
    connues = {}
    for bloc in blocs:
        sous_ensemble, corps = bloc.group(1), bloc.group(2)
        icones = slug == "material-symbols-outlined"
        if not icones and sous_ensemble not in SOUS_ENSEMBLES:
            continue
        famille = re.search(r"font-family:\s*'([^']+)'", corps).group(1)
        graisse = int(re.search(r"font-weight:\s*(\d+)", corps).group(1))
        plage = re.search(r"unicode-range:\s*([^;]+);", corps)
        octets = telecharge(re.search(r"url\((https://[^)]+\.woff2)\)", corps).group(1))
        empreinte = hashlib.md5(octets).hexdigest()

        if empreinte in connues:
            face = connues[empreinte]
            face["min"] = min(face["min"], graisse)
            face["max"] = max(face["max"], graisse)
            continue

        nom = "%s-%s.woff2" % (slug, "icons" if icones else "%d-%s" % (graisse, sous_ensemble))
        with open(os.path.join(DOSSIER, nom), "wb") as fichier:
            fichier.write(octets)
        face = {
            "famille": famille,
            "nom": nom,
            "min": graisse,
            "max": graisse,
            "plage": plage.group(1).strip() if plage else None,
        }
        connues[empreinte] = face
        faces.append(face)

with open(FEUILLE, "w", encoding="utf-8", newline="\n") as feuille:
    feuille.write(
        "/* Genere par build-fonts.py — ne pas editer a la main.\n"
        "   Polices servies depuis l'origine : la CSP de production dit `font-src 'self'`. */\n"
    )
    for face in faces:
        graisse = (
            str(face["min"])
            if face["min"] == face["max"]
            else "%d %d" % (face["min"], face["max"])
        )
        feuille.write("\n@font-face {\n")
        feuille.write("  font-family: '%s';\n" % face["famille"])
        feuille.write("  font-style: normal;\n")
        feuille.write("  font-weight: %s;\n" % graisse)
        feuille.write("  font-display: swap;\n")
        feuille.write("  src: url('./fonts/%s') format('woff2');\n" % face["nom"])
        if face["plage"]:
            feuille.write("  unicode-range: %s;\n" % face["plage"])
        feuille.write("}\n")

    # Google servait aussi cette classe utilitaire avec sa feuille, et `mat-icon` en depend :
    # `MAT_ICON_DEFAULT_OPTIONS` pose `material-symbols-outlined` sur l'element, mais rien
    # d'autre ne lui donne la famille ni n'active les ligatures. La rapatrier fait partie de
    # l'hebergement local — sans elle, les fichiers sont servis et aucune icone n'apparait.
    feuille.write(CLASSE_ICONES)


print("%d faces, %d fichiers" % (len(faces), len(os.listdir(DOSSIER))))
