package com.leadflow.qualification;

/** D'ou vient la cle d'API effectivement utilisee par l'analyse d'intention. */
public enum SourceCle {

    /** Saisie depuis la console : elle l'emporte sur l'environnement. */
    BASE,

    /** Aucune cle en base : celle de {@code GEMINI_API_KEY} sert de repli. */
    ENV,

    /** Ni l'une ni l'autre : la qualification restera en mode {@code RULES}. */
    AUCUNE
}
