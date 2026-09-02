package com.leadflow.routing;

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
 * Un geste humain porte par un lead, et ce qui le justifie.
 *
 * <p>N'herite pas de {@code BaseEntity} : une ligne de journal ne se met jamais a jour,
 * donc {@code updated_at} n'aurait aucun sens. Meme parti que {@code DeadLetter}.
 *
 * <p>Les deux references de commerciaux ne portent pas de cle etrangere : un commercial
 * supprime ne doit pas effacer l'histoire.
 */
@Entity
@Table(name = "lead_action")
@Getter
@Setter
public class LeadAction {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private LeadActionType action;

    @Column(name = "actor", nullable = false, length = 120)
    private String actor;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "previous_sales_rep_id")
    private UUID previousSalesRepId;

    @Column(name = "new_sales_rep_id")
    private UUID newSalesRepId;

    @Column(name = "dead_letter_id")
    private UUID deadLetterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private LeadActionOutcome outcome;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
