package com.leadflow.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Trace d'une tentative de synchronisation vers l'ERP d'un client.
 *
 * <p>Table append-only : chaque tentative ajoute une ligne, aucune n'est modifiee. C'est
 * le support de l'idempotence des adaptateurs en F5 — avant de creer quoi que ce soit,
 * l'adaptateur relit la derniere tentative reussie pour savoir ce qui existe deja.
 *
 * <p>N'herite pas de {@code BaseEntity} : une ligne n'etant jamais modifiee,
 * {@code updated_at} n'aurait aucun sens. Elle porte {@code attempted_at} a la place.
 */
@Entity
@Table(name = "crm_sync_attempt")
@Getter
@Setter
public class CrmSyncAttempt {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    /** Porte par la ligne : un client peut changer d'ERP sans rendre l'historique illisible. */
    @Column(name = "provider_id", nullable = false, length = 40)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CrmSyncAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "nature", nullable = false, length = 20)
    private CrmSyncAttemptNature nature = CrmSyncAttemptNature.SYNCHRONISATION;

    @Column(name = "account_ref", length = 64)
    private String accountRef;

    @Column(name = "contact_ref", length = 64)
    private String contactRef;

    @Column(name = "opportunity_ref", length = 64)
    private String opportunityRef;

    @Column(name = "assignee_ref", length = 64)
    private String assigneeRef;

    @Column(name = "task_ref", length = 64)
    private String taskRef;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @PrePersist
    void onCreate() {
        if (this.attemptedAt == null) {
            this.attemptedAt = Instant.now();
        }
    }
}
