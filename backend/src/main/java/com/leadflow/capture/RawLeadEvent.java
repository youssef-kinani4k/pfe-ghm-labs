package com.leadflow.capture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Journal d'entree du middleware : le payload webhook tel qu'il a ete recu, avant toute
 * qualification. C'est ce qui permet de rejouer un lead perdu ou mal traite.
 *
 * <p>N'herite pas de {@code BaseEntity} : la table vient de V1 et porte {@code received_at}
 * plutot que le couple {@code created_at} / {@code updated_at}.
 */
@Entity
@Table(name = "raw_lead_event")
@Getter
@Setter
public class RawLeadEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Tenant emetteur. Identifiant brut : {@code tenant} est un autre package. */
    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    /** Canal d'origine chez ce client : formulaire de contact, demande de devis... */
    @Column(name = "source", nullable = false, length = 120)
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "signature", nullable = false, length = 255)
    private String signature;

    /**
     * Vrai quand c'est le secret precedent de la boutique qui a valide cette soumission.
     * Pose a l'insertion, donc sans ecriture supplementaire sur le chemin chaud. C'est ce
     * qui permet de dire a l'operateur si la boutique a fini de migrer.
     */
    @Column(name = "signed_with_previous_secret", nullable = false)
    private boolean signedWithPreviousSecret;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RawLeadEventStatus status = RawLeadEventStatus.RECEIVED;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @PrePersist
    void onCreate() {
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
    }
}
