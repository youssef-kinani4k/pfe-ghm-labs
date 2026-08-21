package com.leadflow.tenant;

import com.leadflow.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Commercial rattache a un client. L'association vers {@link Client} est autorisee car
 * les deux entites vivent dans le meme package ; entre packages, on porte l'identifiant.
 */
@Entity
@Table(name = "sales_rep",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sales_rep_client_email",
                columnNames = {"client_id", "email"}))
@Getter
@Setter
public class SalesRep extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** Reference du commercial dans l'ERP du client, nulle tant qu'elle n'est pas resolue. */
    @Column(name = "crm_ref", length = 64)
    private String crmRef;

    @Column(name = "sector", length = 80)
    private String sector;

    @Column(name = "zone", length = 80)
    private String zone;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
