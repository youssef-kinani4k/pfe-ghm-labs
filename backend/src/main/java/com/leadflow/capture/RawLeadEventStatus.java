package com.leadflow.capture;

/** Cycle de vie d'un evenement brut, de sa reception a sa publication sur le broker. */
public enum RawLeadEventStatus {
    RECEIVED,
    PUBLISHED,
    FAILED
}
