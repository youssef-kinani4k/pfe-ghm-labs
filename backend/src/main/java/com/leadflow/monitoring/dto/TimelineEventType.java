package com.leadflow.monitoring.dto;

/**
 * Les six faits qu'un lead peut avoir vecus. L'ordre de declaration est l'ordre du
 * pipeline, et {@code LeadTimelineService} s'en sert pour placer une attribution non datee.
 */
public enum TimelineEventType {
    CAPTURE,
    QUALIFICATION,
    ATTRIBUTION,
    SYNC_ERP,
    MORT,
    REJEU
}
