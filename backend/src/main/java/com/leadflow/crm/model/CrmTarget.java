package com.leadflow.crm.model;

import java.util.Map;

/**
 * Instance ERP visee par une synchronisation : le fournisseur et les reglages de
 * connexion propres au client concerne.
 *
 * <p>Volontairement sans champ {@code baseUrl} : rien ne garantit qu'un ERP futur
 * s'adresse par URL. Les cles de {@code settings} sont interpretees par l'adaptateur, qui
 * valide a son demarrage ce dont il a besoin — c'est ce qui permet d'ajouter un ERP sans
 * migration ni modification du modele pivot.
 */
public record CrmTarget(String providerId, Map<String, String> settings) {
}
