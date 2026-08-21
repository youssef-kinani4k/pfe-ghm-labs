/**
 * Adaptateur Dolibarr (API REST, authentification par cle {@code DOLAPIKEY}).
 *
 * <p>Correspondance avec le modele du domaine : {@code CrmAccount} vers Tiers
 * ({@code /thirdparties}), {@code CrmContact} vers {@code /contacts},
 * {@code CrmOpportunity} vers Projet ou Proposition, {@code CrmTask} vers evenement
 * d'agenda ({@code /agendaevents}).
 */
package com.leadflow.crm.dolibarr;
