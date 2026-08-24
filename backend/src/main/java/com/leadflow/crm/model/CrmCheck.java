package com.leadflow.crm.model;

/**
 * Resultat d'une sonde d'acces.
 *
 * <p>{@code detail} porte le message technique d'origine, tronque : l'ecran le replie sous
 * la phrase lisible, pour l'exploitant qui diagnostique lui-meme.
 */
public record CrmCheck(CrmCheckCause cause, String detail) {

    private static final int DETAIL_MAX = 500;

    public static CrmCheck joignable(String detail) {
        return new CrmCheck(CrmCheckCause.JOIGNABLE, tronque(detail));
    }

    public static CrmCheck echec(CrmCheckCause cause, String detail) {
        return new CrmCheck(cause, tronque(detail));
    }

    public boolean ok() {
        return cause == CrmCheckCause.JOIGNABLE;
    }

    private static String tronque(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) : detail;
    }
}
