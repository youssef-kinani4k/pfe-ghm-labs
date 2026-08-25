package com.leadflow.tenant;

/**
 * Traduite en 409 : retirer le dernier commercial actif d'une boutique active ferait lever
 * AssignmentException au routage, donc partir ses leads en DLQ des le prochain formulaire.
 */
public class DernierCommercialException extends RuntimeException {
    public DernierCommercialException(String message) {
        super(message);
    }
}
