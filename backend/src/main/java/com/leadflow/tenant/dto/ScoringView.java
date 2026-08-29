package com.leadflow.tenant.dto;

import com.leadflow.qualification.ScoringConfig;

/**
 * Ce que l'ecran affiche : les valeurs <b>effectives</b> — defauts compris, car c'est le
 * bareme que {@code LeadScorer} applique reellement — plus deux calculs qui remplacent
 * l'apercu volontairement absent.
 *
 * <p>{@code scoreMaximum} est borne a 100 comme le score lui-meme, et
 * {@code seuilInatteignable} dit qu'aucun lead ne pourra jamais etre chaud avec ce bareme.
 * Ce n'est pas une erreur de saisie — c'est un etat qu'il faut voir.
 */
public record ScoringView(
        ScoringForm valeurs,
        int scoreMaximum,
        boolean seuilInatteignable,
        ScoringForm defauts) {

    private static final int PLAFOND = 100;

    public static ScoringView de(ScoringConfig bareme) {
        int meilleureIntention = bareme.intention().values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int maximum = Math.min(
                PLAFOND,
                bareme.telephonePresent()
                        + bareme.societePresente()
                        + bareme.nomPresent()
                        + bareme.messagePresent()
                        + meilleureIntention
                        + bareme.bonusCible());
        return new ScoringView(
                ScoringForm.de(bareme),
                maximum,
                bareme.seuilChaud() > maximum,
                ScoringForm.de(ScoringConfig.defaut()));
    }
}
