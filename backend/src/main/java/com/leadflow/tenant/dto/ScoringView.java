package com.leadflow.tenant.dto;

import com.leadflow.qualification.ScoringConfig;

/**
 * Ce que l'ecran affiche : les valeurs <b>effectives</b> — defauts compris, car c'est le
 * bareme que {@code LeadScorer} applique reellement — plus deux calculs qui remplacent
 * l'apercu volontairement absent.
 *
 * <p>{@code scoreMaximum} est encadre par {@code [0, 100]} <b>exactement comme
 * {@code LeadScorer} encadre le score qu'il rend</b> : la vue annonce ce que le scoreur
 * produira, elle ne doit donc pas pouvoir annoncer une valeur qu'il ne rendrait jamais. La
 * borne basse n'est pas symetrique par gout — les bornes de {@code ScoringForm} ne tiennent
 * qu'a l'ecriture, alors que la lecture passe par {@code ScoringConfig.depuis}, tolerante par
 * construction : un document ecrit avant F8, ou pose a la main en base, peut porter un poids
 * negatif et donnerait sans elle un maximum negatif a l'ecran.
 *
 * <p>{@code seuilInatteignable} dit qu'aucun lead ne pourra jamais etre chaud avec ce bareme.
 * Ce n'est pas une erreur de saisie — c'est un etat qu'il faut voir.
 */
public record ScoringView(
        ScoringForm valeurs,
        int scoreMaximum,
        boolean seuilInatteignable,
        ScoringForm defauts) {

    private static final int PLANCHER = 0;
    private static final int PLAFOND = 100;

    public static ScoringView de(ScoringConfig bareme) {
        int meilleureIntention = bareme.intention().values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int total = bareme.telephonePresent()
                + bareme.societePresente()
                + bareme.nomPresent()
                + bareme.messagePresent()
                + meilleureIntention
                + bareme.bonusCible();
        int maximum = Math.max(PLANCHER, Math.min(PLAFOND, total));
        return new ScoringView(
                ScoringForm.de(bareme),
                maximum,
                bareme.seuilChaud() > maximum,
                ScoringForm.de(ScoringConfig.defaut()));
    }
}
