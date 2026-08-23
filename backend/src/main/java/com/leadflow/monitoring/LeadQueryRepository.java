package com.leadflow.monitoring;

import com.leadflow.qualification.Lead;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/**
 * Repository propre au monitoring.
 *
 * <p>{@code LeadRepository} appartient a la qualification et porte les requetes de la
 * deduplication et du tour de role ; y greffer {@code JpaSpecificationExecutor} pour les
 * besoins d'un ecran melangerait deux responsabilites dans une interface que F3 et F4
 * lisent deja. Spring Data accepte plusieurs repositories pour une meme entite.
 *
 * <p>{@code Repository} nu et non {@code JpaRepository} : aucune methode d'ecriture n'est
 * meme exposee, ce qui rend verifiable a la lecture la regle « le monitoring n'ecrit pas ».
 */
public interface LeadQueryRepository
        extends Repository<Lead, UUID>, JpaSpecificationExecutor<Lead> {
}
