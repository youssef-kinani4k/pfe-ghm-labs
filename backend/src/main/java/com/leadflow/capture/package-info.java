/**
 * Etape 1 - Capture et reception securisee.
 *
 * <p>Expose les endpoints webhook consommes par les formulaires des sites clients,
 * verifie la signature HMAC du payload, persiste l'evenement brut dans
 * {@code raw_lead_event} puis publie sur RabbitMQ. Aucun traitement metier ici :
 * l'objectif est d'accuser reception le plus vite possible pour ne perdre aucun lead.
 */
package com.leadflow.capture;
