package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.LeadSummary;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.qualification.Lead;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture des leads pour le dashboard.
 *
 * <p>{@code Lead} ne porte pas d'association JPA vers {@code client} ni {@code sales_rep} —
 * choix de F1 qui garde les etapes decouplees — donc aucun {@code join fetch} n'est
 * possible. Les noms sont resolus en deux requetes supplementaires par page, bornees par la
 * taille de la page : trois requetes au total plutot qu'un N+1, et surtout aucune
 * association ajoutee aux entites du pipeline pour le confort d'un ecran.
 *
 * <p>{@code open-in-view} est a false : la conversion en DTO se fait ici, sous transaction.
 */
@Service
public class LeadQueryService {

    private final LeadQueryRepository leads;
    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;

    public LeadQueryService(
            LeadQueryRepository leads,
            ClientRepository clients,
            SalesRepRepository commerciaux) {
        this.leads = leads;
        this.clients = clients;
        this.commerciaux = commerciaux;
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadSummary> cherche(LeadFilter filtre, Pageable pagination) {
        Page<Lead> page = leads.findAll(LeadSpecifications.depuis(filtre), pagination);

        Map<UUID, String> nomsDeClient = nomsDeClient(page.getContent());
        Map<UUID, String> nomsDeCommercial = nomsDeCommercial(page.getContent());

        List<LeadSummary> lignes = page.getContent().stream()
                .map(lead -> new LeadSummary(
                        lead.getId(),
                        lead.getCreatedAt(),
                        lead.getClientId(),
                        nom(nomsDeClient, lead.getClientId()),
                        lead.getCompanyName(),
                        lead.getEmail(),
                        lead.getDetectedIntent(),
                        lead.getIntentSource(),
                        lead.getScore(),
                        lead.getStatus(),
                        lead.getAssignedSalesRepId(),
                        nom(nomsDeCommercial, lead.getAssignedSalesRepId()),
                        lead.getCountryCode(),
                        lead.getSector()))
                .toList();

        return PageResponse.de(page, lignes);
    }

    /**
     * Recherche tolerante a l'identifiant nul. {@code Map.of()} — rendu quand la page ne
     * contient aucun identifiant a resoudre — leve sur une cle nulle au lieu de rendre
     * null : un lead non encore attribue ferait tomber toute la page.
     */
    private String nom(Map<UUID, String> noms, UUID identifiant) {
        return identifiant == null ? null : noms.get(identifiant);
    }

    private Map<UUID, String> nomsDeClient(List<Lead> page) {
        Set<UUID> identifiants = page.stream()
                .map(Lead::getClientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        return clients.findAllById(identifiants).stream()
                .collect(Collectors.toMap(Client::getId, Client::getName));
    }

    /**
     * Les leads non encore attribues portent un identifiant nul : les filtrer avant la
     * requete evite un {@code in (null)} et, surtout, une carte qui refuserait la cle nulle
     * a la construction.
     */
    private Map<UUID, String> nomsDeCommercial(List<Lead> page) {
        Set<UUID> identifiants = page.stream()
                .map(Lead::getAssignedSalesRepId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        return commerciaux.findAllById(identifiants).stream()
                .collect(Collectors.toMap(SalesRep::getId, SalesRep::getFullName));
    }
}
