package com.leadflow.monitoring.deadletter;

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
 * Un message mort, journalise pour etre lisible et rejouable.
 *
 * <p>N'herite pas de {@code BaseEntity} : {@code created_at} / {@code updated_at} n'ont pas
 * de sens ici. Une mort a une date de deces et, eventuellement, une date de rejeu ; les
 * deux sont metier et portent des noms metier.
 *
 * <p>{@code payload} est le texte exact recu. Le journal ne deserialise jamais : c'est ce
 * qui lui permet d'ecrire une ligne pour un message illisible, et de rejouer des octets
 * identiques a ceux qui sont partis.
 */
@Entity
@Table(name = "dead_letter")
@Getter
@Setter
public class DeadLetter {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "origin_queue", nullable = false, length = 80)
    private String originQueue;

    /** Cle de routage d'origine, celle dont le rejeu a besoin pour republier au bon endroit. */
    @Column(name = "routing_key", nullable = false, length = 80)
    private String routingKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "content_type", length = 80)
    private String contentType;

    /** En-tete {@code __TypeId__}, sans lequel le rejeu ne peut pas etre deserialise. */
    @Column(name = "type_id", length = 255)
    private String typeId;

    @Column(name = "client_id")
    private UUID clientId;

    /** Sans cle etrangere : un message corrompu doit pouvoir etre journalise malgre tout. */
    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Column(name = "dead_at", nullable = false)
    private Instant deadAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeadLetterStatus status = DeadLetterStatus.PENDING;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    /** Nom de l'operateur tire du jeton. Seule trace de qui a fait quoi dans tout F6. */
    @Column(name = "replayed_by", length = 120)
    private String replayedBy;

    @PrePersist
    void onCreate() {
        if (this.deadAt == null) {
            this.deadAt = Instant.now();
        }
    }
}
