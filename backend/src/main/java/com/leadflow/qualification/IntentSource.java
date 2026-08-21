package com.leadflow.qualification;

/**
 * Analyseur ayant produit l'intention detectee. Rend le basculement en mode degrade
 * observable au lieu d'etre seulement affirme.
 */
public enum IntentSource {
    RULES,
    GEMINI
}
