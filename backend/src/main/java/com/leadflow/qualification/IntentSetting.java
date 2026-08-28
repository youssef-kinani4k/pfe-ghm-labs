package com.leadflow.qualification;

import com.leadflow.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Le reglage de l'analyse d'intention, en une ligne unique.
 *
 * <p>N'herite pas de {@code BaseEntity} : il n'y a pas de collection d'objets ici, donc pas
 * d'identifiant a generer. L'identifiant vaut toujours 1, et la contrainte de la migration
 * {@code V5} rend la seconde ligne impossible cote base plutot que cote code.
 */
@Entity
@Table(name = "intent_setting")
@Getter
@Setter
public class IntentSetting {

    /** Ligne unique : la valeur est ecrite en dur et la base refuse toute autre. */
    @Id
    @Column(name = "id", nullable = false)
    private Short id = 1;

    /** Chiffree au repos. {@code null} signifie « aucune cle en base ». */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_key", columnDefinition = "text")
    private String apiKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
