package com.leadflow.crm.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CrmSyncExceptionTest {

    @Test
    void sansEtatExpliciteLExceptionPorteUnEtatVierge() {
        CrmSyncException echec = new CrmSyncException("dolibarr", "instance injoignable", null);

        assertThat(echec.partialState()).isEqualTo(CrmSyncState.VIERGE);
    }

    @Test
    void avecEtatConserveLeMessageLeProviderEtLaCause() {
        Throwable cause = new IllegalStateException("socket fermee");
        CrmSyncException initiale = new CrmSyncException("dolibarr", "echec sur l'opportunite", cause);

        CrmSyncException enrichie = initiale.avecEtat(new CrmSyncState("42", "77", null));

        assertThat(enrichie.providerId()).isEqualTo("dolibarr");
        assertThat(enrichie.getMessage()).isEqualTo("echec sur l'opportunite");
        assertThat(enrichie.getCause()).isSameAs(cause);
        assertThat(enrichie.partialState().accountRef()).isEqualTo("42");
        assertThat(enrichie.partialState().contactRef()).isEqualTo("77");
        assertThat(enrichie.partialState().opportunityRef()).isNull();
    }
}
