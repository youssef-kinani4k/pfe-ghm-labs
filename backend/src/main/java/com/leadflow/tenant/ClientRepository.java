package com.leadflow.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    /**
     * Resout le client emetteur d'un webhook depuis la cle publique de son URL. Un client
     * desactive est introuvable : c'est ainsi qu'on coupe un site sans supprimer ses donnees.
     */
    Optional<Client> findByPublicKeyAndActiveTrue(String publicKey);
}
