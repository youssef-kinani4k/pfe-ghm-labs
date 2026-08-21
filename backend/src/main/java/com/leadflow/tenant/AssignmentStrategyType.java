package com.leadflow.tenant;

/**
 * Strategie d'attribution des leads aux commerciaux. Choisie par client, consommee par
 * la couche routing en F4.
 */
public enum AssignmentStrategyType {
    ROUND_ROBIN,
    GEOGRAPHIC,
    SECTOR
}
