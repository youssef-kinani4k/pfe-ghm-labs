package com.leadflow.tenant;

import com.leadflow.common.BaseEntity;
import com.leadflow.common.EncryptedJsonConverter;
import com.leadflow.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Le tenant : un site client de l'agence. Porte tout ce qui lui est propre — son secret
 * de signature, l'ERP qu'il vise et les parametres de connexion a SON instance.
 */
@Entity
@Table(name = "client")
@Getter
@Setter
public class Client extends BaseEntity {

    /** Cle publique figurant dans l'URL du webhook. Non secrete, revocable. */
    @Column(name = "public_key", nullable = false, unique = true, length = 64)
    private String publicKey;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "hmac_secret", nullable = false, columnDefinition = "text")
    private String hmacSecret;

    /**
     * Secret precedent, encore accepte jusqu'a {@code previousSecretExpiresAt}. Nul hors
     * transition. Chiffre au repos par le meme converter que {@code hmacSecret} : les deux
     * sont la meme chose a un instant different.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "previous_hmac_secret", columnDefinition = "text")
    private String previousHmacSecret;

    /**
     * Fin de la fenetre de transition. Toujours posee et effacee en meme temps que
     * {@code previousHmacSecret} : un secret precedent sans expiration serait un second
     * secret permanent, donc le double de surface d'attaque pour la meme porte.
     */
    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    @Column(name = "crm_provider_id", nullable = false, length = 40)
    private String crmProviderId;

    /** Reglages de connexion a l'ERP du client. Les cles dependent du fournisseur. */
    @Convert(converter = EncryptedJsonConverter.class)
    @Column(name = "crm_config", nullable = false, columnDefinition = "text")
    private Map<String, String> crmConfig = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_strategy", nullable = false, length = 32)
    private AssignmentStrategyType assignmentStrategy = AssignmentStrategyType.ROUND_ROBIN;

    /** Regles de scoring. Forme definie par F3 ; volontairement non typee ici. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scoring_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scoringConfig = new HashMap<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
