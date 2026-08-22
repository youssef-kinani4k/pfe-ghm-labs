package com.leadflow.common;

/**
 * Une seule exception pour les cinq causes de refus : cle publique inconnue, client
 * desactive, en-tete absent, signature fausse, horodatage hors fenetre.
 *
 * <p>C'est deliberement une seule classe : distinguer les causes cote HTTP offrirait a qui
 * sonde l'API un oracle sur les cles publiques existantes et sur l'etat des clients. Le
 * message porte la raison interne, destinee aux logs serveur, jamais a la reponse.
 */
public class WebhookAuthenticationException extends RuntimeException {

    public WebhookAuthenticationException(String raisonInterne) {
        super(raisonInterne);
    }
}
