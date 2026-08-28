package com.leadflow.qualification;

/**
 * Ce que l'ecran d'administration a le droit de savoir de la cle d'API.
 *
 * @param actif interrupteur de l'analyse par le modele ; a {@code false}, mode lexical
 * @param cleDefinie une cle existe, en base ou dans l'environnement
 * @param apercu les quatre derniers caracteres de la cle, jamais davantage : de quoi
 *     reconnaitre la cle en place sans permettre de s'en servir. {@code null} s'il n'y en
 *     a aucune
 * @param source d'ou vient la cle effective
 * @param modele identifiant du modele appele, pour affichage
 */
public record EtatIntent(
        boolean actif, boolean cleDefinie, String apercu, SourceCle source, String modele) {
}
