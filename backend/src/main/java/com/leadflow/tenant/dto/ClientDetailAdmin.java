package com.leadflow.tenant.dto;

import com.leadflow.tenant.AssignmentStrategyType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fiche d'une boutique.
 *
 * <p>{@code crmSettings} ne porte que les reglages <b>non secrets</b> : le formulaire doit
 * pouvoir reafficher l'adresse du serveur, jamais la cle d'API. Le secret HMAC n'y figure
 * a aucun titre — il n'est rendu qu'a la creation et a la rotation.
 *
 * <p>{@code transition} est nul hors transition — l'etat de toutes les boutiques qui n'ont
 * pas tourne leur secret recemment.
 */
public record ClientDetailAdmin(
        UUID id,
        String name,
        String publicKey,
        String webhookPath,
        String crmProviderId,
        Map<String, String> crmSettings,
        AssignmentStrategyType assignmentStrategy,
        boolean active,
        List<SalesRepAdminView> salesReps,
        TransitionSecret transition) {
}
