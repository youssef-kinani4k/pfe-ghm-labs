package com.leadflow.routing;

import java.util.UUID;

/**
 * Aucun commercial ne peut recevoir ce lead.
 *
 * <p>Le seul echec du routage qui merite la DLQ, et il la merite parce qu'un humain peut le
 * reparer : activer un commercial, puis rejouer le message. Cela le distingue de l'evenement
 * sans email exploitable de F3, qui echouerait eternellement a l'identique et recoit pour
 * cela un statut terminal.
 */
public class AssignmentException extends RuntimeException {

    public AssignmentException(UUID clientId, UUID leadId) {
        super("Aucun commercial actif pour le client " + clientId
                + " : le lead " + leadId + " ne peut pas etre attribue");
    }
}
