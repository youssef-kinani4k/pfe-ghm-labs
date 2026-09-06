package com.leadflow.monitoring.dto;

/**
 * Les dix faits qu'un lead peut avoir vecus. L'ordre de declaration est l'ordre du
 * pipeline, et {@code LeadTimelineService} s'en sert pour placer une entree non datee.
 */
public enum TimelineEventType {
    CAPTURE,
    QUALIFICATION,
    ATTRIBUTION,
    // Apres ATTRIBUTION : l'ordre de declaration est l'ordre du pipeline, et il sert a
    // placer une entree non datee. Une reattribution suit toujours une attribution.
    REATTRIBUTION,
    SYNC_ERP,
    // Apres SYNC_ERP : on ne corrige le responsable que d'un lead deja present dans l'ERP.
    REAFFECTATION_ERP,
    // Apres REAFFECTATION_ERP : on ne previent le commercial qu'une fois le lead arrive
    // chez lui dans l'ERP. Prevenir plus tot l'enverrait chercher une fiche qui n'existe
    // pas encore.
    NOTIFICATION,
    MORT,
    // Apres REJEU : un ecart suit toujours une mort, comme un rejeu.
    REJEU,
    ECART
}
