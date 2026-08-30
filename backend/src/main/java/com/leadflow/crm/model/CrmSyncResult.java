package com.leadflow.crm.model;

import java.time.Instant;

/**
 * References renvoyees par l'ERP apres synchronisation. Les identifiants sont typees en
 * {@code String} : Dolibarr renvoie des entiers, Odoo des entiers, d'autres ERP des UUID.
 */
public record CrmSyncResult(
        String providerId,
        String accountRef,
        String contactRef,
        String opportunityRef,
        String assigneeRef,
        String taskRef,
        Instant syncedAt) {
}
