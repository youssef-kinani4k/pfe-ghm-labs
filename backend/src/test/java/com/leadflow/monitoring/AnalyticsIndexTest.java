package com.leadflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.leadflow.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les deux index de V10 existent reellement.
 *
 * <p>Les requetes de F13 sont natives : Hibernate ne les valide pas au demarrage, et un index
 * absent ne se verrait qu'en production, sous la forme d'un ecran lent. Ce test le dit tout de
 * suite.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional(readOnly = true)
class AnalyticsIndexTest {

    @Autowired private EntityManager em;

    @Test
    void lesDeuxIndexDeV10Existent() {
        @SuppressWarnings("unchecked")
        List<String> noms = em.createNativeQuery(
                        "select indexname from pg_indexes where schemaname = 'public'")
                .getResultList();

        assertThat(noms)
                .contains("idx_lead_client_created", "idx_crm_sync_attempt_success_at");
    }

    @Test
    void lIndexDesSuccesEstPartiel() {
        String definition = (String) em.createNativeQuery(
                        """
                        select indexdef from pg_indexes
                        where schemaname = 'public'
                          and indexname = 'idx_crm_sync_attempt_success_at'
                        """)
                .getSingleResult();

        // Partiel et non global : la requete des delais ne lit que les succes, et un index
        // sur toutes les tentatives ferait payer les echecs, qui sont l'essentiel du volume
        // quand un ERP tombe.
        assertThat(definition).contains("WHERE").contains("SUCCESS");
    }
}
