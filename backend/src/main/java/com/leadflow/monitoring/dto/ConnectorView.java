package com.leadflow.monitoring.dto;

import java.time.Instant;
import java.util.List;

/**
 * Etat d'un fournisseur ERP, <b>derive des traces</b> et jamais d'un appel vers l'ERP : il
 * faudrait dechiffrer le crm_config de chaque client pour construire l'appel, un ERP lent
 * bloquerait le chargement de l'ecran, et le port CrmConnector n'a pas d'operation de
 * sante — lui en ajouter une obligerait chaque futur adaptateur a l'implementer.
 *
 * <p>{@code enabled} vient de la configuration, le reste des traces : c'est ce qui permet a
 * l'ecran de distinguer un fournisseur desactive d'un fournisseur actif dont la derniere
 * synchronisation a echoue.
 */
public record ConnectorView(
        String providerId,
        boolean implemented,
        boolean enabled,
        long successCount,
        long failureCount,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String lastFailureMessage,
        List<ConnectorClientActivity> parClient) {
}
