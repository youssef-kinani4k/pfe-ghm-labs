package com.leadflow.monitoring.dto;

/**
 * Issue d'une entree, pour que l'ecran marque les echecs sans avoir a interpreter les
 * details. {@code NEUTRE} est le cas des faits qui ne reussissent ni n'echouent — une
 * capture, une attribution.
 */
public enum TimelineOutcome {
    SUCCES,
    ECHEC,
    NEUTRE
}
