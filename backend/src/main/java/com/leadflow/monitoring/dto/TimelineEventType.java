package com.leadflow.monitoring.dto;

/**
 * Les huit faits qu'un lead peut avoir vecus. L'ordre de declaration est l'ordre du
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
    MORT,
    // Apres REJEU : un ecart suit toujours une mort, comme un rejeu.
    REJEU,
    ECART
}
