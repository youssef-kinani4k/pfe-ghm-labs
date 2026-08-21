package com.leadflow.qualification;

/**
 * Cycle de vie d'un lead qualifie. Il nait {@code QUALIFIED} puisque c'est la
 * qualification qui l'ecrit ; {@code REJECTED} couvre le doublon et les donnees
 * invalides, {@code FAILED} un echec de traitement en aval.
 */
public enum LeadStatus {
    QUALIFIED,
    ROUTED,
    SYNCED,
    REJECTED,
    FAILED
}
