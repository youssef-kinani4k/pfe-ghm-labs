package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.ClientSummary;
import com.leadflow.monitoring.dto.SalesRepSummary;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Annuaire de reference du dashboard : ce qu'il faut pour alimenter les listes deroulantes
 * de filtre, et rien de plus.
 *
 * <p>Aucune pagination : le nombre de clients d'une agence tient sur un ecran, et un filtre
 * qui se chargerait en deux temps serait moins utilisable qu'une liste complete.
 *
 * <p>La conversion en {@code record} n'est pas de la ceremonie. {@code Client} porte
 * {@code hmacSecret} et {@code crmConfig} que les {@code AttributeConverter} dechiffrent a
 * la lecture : renvoyer l'entite, ne serait-ce qu'une fois par inadvertance, publierait le
 * secret HMAC d'un client sur HTTP.
 */
@Service
public class ClientDirectoryService {

    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;

    public ClientDirectoryService(ClientRepository clients, SalesRepRepository commerciaux) {
        this.clients = clients;
        this.commerciaux = commerciaux;
    }

    @Transactional(readOnly = true)
    public List<ClientSummary> tousLesClients() {
        return clients.findAll(Sort.by("name")).stream()
                .map(this::resume)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SalesRepSummary> commerciauxDe(UUID clientId) {
        if (!clients.existsById(clientId)) {
            throw new RessourceIntrouvableException("Client inconnu : " + clientId);
        }
        return commerciaux.findByClientIdOrderByFullName(clientId).stream()
                .map(this::resume)
                .toList();
    }

    private ClientSummary resume(Client client) {
        return new ClientSummary(
                client.getId(),
                client.getName(),
                client.isActive(),
                client.getCrmProviderId(),
                client.getAssignmentStrategy());
    }

    private SalesRepSummary resume(SalesRep commercial) {
        return new SalesRepSummary(
                commercial.getId(),
                commercial.getFullName(),
                commercial.getEmail(),
                commercial.getSector(),
                commercial.getZone(),
                commercial.isActive(),
                commercial.getCrmRef());
    }
}
