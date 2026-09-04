package com.leadflow.notification;

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
 * Trace d'une tentative de notification d'un commercial.
 *
 * <p>Table append-only : chaque tentative ajoute une ligne, aucune n'est modifiee. Elle
 * n'herite donc pas de {@code BaseEntity} — {@code updated_at} n'aurait aucun sens —, comme
 * {@code CrmSyncAttempt}, {@code DeadLetter} et {@code LeadAction}.
 *
 * <p>{@code score} et {@code seuil} sont figes dans la ligne plutot que relus : le seuil se
 * regle depuis l'ecran « Bareme », et le deplacer ne doit pas rendre incomprehensible une
 * decision deja prise. C'est le meme parti que {@code lead.score}, fige a la qualification.
 */
@Entity
@Table(name = "notification_attempt")
@Getter
@Setter
public class NotificationAttempt {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    /** Porte par la ligne : l'instance peut changer de canal sans rendre l'historique illisible. */
    @Column(name = "channel", nullable = false, length = 40)
    private String channel;

    /** L'adresse telle qu'elle a servi, jamais relue depuis {@code sales_rep}. */
    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    /** Sans cle etrangere : un commercial supprime ne doit pas effacer la trace. */
    @Column(name = "sales_rep_id")
    private UUID salesRepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private NotificationStatus status;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "seuil", nullable = false)
    private int seuil;

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
