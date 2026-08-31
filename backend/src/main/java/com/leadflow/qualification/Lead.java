package com.leadflow.qualification;

import com.leadflow.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Lead qualifie : l'evenement brut apres nettoyage, deduplication, analyse d'intention et
 * scoring.
 *
 * <p>Les references vers {@code client}, {@code raw_lead_event} et {@code sales_rep} sont
 * portees en identifiants bruts et non en associations : ces entites vivent dans d'autres
 * packages, et le typage direct rendrait {@code qualification} dependant de {@code tenant}
 * et de {@code capture}.
 */
@Entity
@Table(name = "lead")
@Getter
@Setter
public class Lead extends BaseEntity {

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    /** Unique en base : un evenement brut ne peut produire qu'un seul lead. */
    @Column(name = "raw_event_id", nullable = false, unique = true)
    private UUID rawEventId;

    @Column(name = "company_name", length = 160)
    private String companyName;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "detected_intent", length = 64)
    private String detectedIntent;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent_source", length = 32)
    private IntentSource intentSource;

    @Column(name = "score", nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LeadStatus status = LeadStatus.QUALIFIED;

    @Column(name = "assigned_sales_rep_id")
    private UUID assignedSalesRepId;

    /**
     * Date d'attribution, posee par le routage en meme temps que le statut et le
     * commercial. Nulle pour les leads attribues avant la migration V7 : la timeline
     * affiche alors « date inconnue » plutot qu'une date deduite, qui serait fausse.
     */
    @Column(name = "routed_at")
    private Instant routedAt;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "sector", length = 80)
    private String sector;
}
