package com.leadflow.monitoring;

import com.leadflow.monitoring.dto.LeadSummary;
import com.leadflow.monitoring.dto.PageResponse;
import com.leadflow.qualification.Lead;
import com.leadflow.qualification.ScoringConfig;
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
 *
 * <p>Le drapeau {@code chaud} se calcule ici et non en base : le seuil vit dans
 * {@code client.scoring_config}, un document chiffre au repos que Postgres ne sait pas lire.
 * Les boutiques de la page sont donc chargees entieres — elles l'etaient deja pour leur nom,
 * et garder l'entite au lieu du seul nom ne coute aucune requete de plus.
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
        // Les seuils ne sont charges que si le filtre les reclame : la liste sans filtre est
        // le cas courant, et elle n'a pas a payer une lecture de toutes les boutiques.
        Map<UUID, Integer> seuilsDuFiltre = Boolean.TRUE.equals(filtre.chaud())
                ? seuils(filtre.clientId())
                : Map.of();
        Page<Lead> page = leads.findAll(
                LeadSpecifications.depuis(filtre, seuilsDuFiltre), pagination);

        Map<UUID, Client> boutiques = boutiques(page.getContent());
        Map<UUID, String> nomsDeCommercial = nomsDeCommercial(page.getContent());

        List<LeadSummary> lignes = page.getContent().stream()
                .map(lead -> new LeadSummary(
                        lead.getId(),
                        lead.getCreatedAt(),
                        lead.getClientId(),
                        nomDeBoutique(boutiques, lead.getClientId()),
                        lead.getCompanyName(),
                        lead.getEmail(),
                        lead.getDetectedIntent(),
                        lead.getIntentSource(),
                        lead.getScore(),
                        lead.getStatus(),
                        lead.getAssignedSalesRepId(),
                        nom(nomsDeCommercial, lead.getAssignedSalesRepId()),
                        lead.getCountryCode(),
                        lead.getSector(),
                        estChaud(lead, boutiques)))
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

    /**
     * Meme tolerance que ci-dessus, sur la boutique plutot que sur son seul nom. Le nom
     * differe de {@code nom} parce que l'effacement de type rendrait les deux signatures
     * identiques.
     */
    private String nomDeBoutique(Map<UUID, Client> boutiques, UUID identifiant) {
        Client boutique = identifiant == null ? null : boutiques.get(identifiant);
        return boutique == null ? null : boutique.getName();
    }

    /**
     * Les boutiques de la page, entieres : le nom et le seuil en sortent ensemble, la ou la
     * version precedente ne gardait que le nom.
     */
    private Map<UUID, Client> boutiques(List<Lead> page) {
        Set<UUID> identifiants = page.stream()
                .map(Lead::getClientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (identifiants.isEmpty()) {
            return Map.of();
        }
        return clients.findAllById(identifiants).stream()
                .collect(Collectors.toMap(Client::getId, boutique -> boutique));
    }

    /**
     * Seuils de toutes les boutiques, ou d'une seule quand le filtre en designe une. Charger
     * toutes les boutiques est acceptable a l'echelle d'une agence, et c'est le seul moyen :
     * le seuil n'est pas requetable en SQL.
     */
    private Map<UUID, Integer> seuils(UUID clientId) {
        List<Client> boutiques = clientId == null
                ? clients.findAll()
                : clients.findById(clientId).stream().toList();
        return boutiques.stream()
                .collect(Collectors.toMap(
                        Client::getId,
                        boutique -> ScoringConfig.depuis(boutique.getScoringConfig())
                                .seuilChaud()));
    }

    /**
     * Un lead dont la boutique a disparu n'est pas chaud : sans seuil, il n'y a pas de
     * verdict a rendre, et {@code false} est le seul defaut qui ne mente pas a l'ecran.
     */
    private boolean estChaud(Lead lead, Map<UUID, Client> boutiques) {
        Client boutique = lead.getClientId() == null ? null : boutiques.get(lead.getClientId());
        if (boutique == null) {
            return false;
        }
        return lead.getScore()
                >= ScoringConfig.depuis(boutique.getScoringConfig()).seuilChaud();
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
