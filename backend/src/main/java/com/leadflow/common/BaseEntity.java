package com.leadflow.common;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Plomberie commune aux entites : identifiant et horodatages.
 *
 * <p>L'identifiant est un UUID ordonne dans le temps, genere cote application. Deux
 * consequences : les index ne se fragmentent pas comme avec un UUID aleatoire, et
 * l'identifiant existe avant l'insertion — ce dont la capture aura besoin en F2 pour
 * publier sur RabbitMQ sans aller-retour en base.
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
