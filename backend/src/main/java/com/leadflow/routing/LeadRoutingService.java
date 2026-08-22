package com.leadflow.routing;

import com.leadflow.qualification.Lead;
import com.leadflow.qualification.LeadRepository;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import com.leadflow.tenant.SalesRep;
import com.leadflow.tenant.SalesRepRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Choisit le commercial destinataire d'un lead qualifie.
 *
 * <p><b>Volontairement non transactionnel</b>, comme {@code LeadQualificationService} :
 * l'ecriture a sa propre transaction, portee par {@link RoutedLeadWriter}, et la suite du
 * traitement part apres son commit.
 *
 * <p>La publication part apres le retour de {@link RoutedLeadWriter#attribue}, donc apres le
 * commit : un message parti plus tot designerait un lead que l'etape suivante lirait encore
 * sans commercial.
 *
 * <p>C'est ici, et pas dans les strategies, que vivent les trois decisions qui ne sont pas
 * du calcul : ne pas reattribuer un lead qui porte deja un commercial, lever quand aucun
 * commercial n'est actif, et journaliser le repli d'une strategie qui n'a trouve personne
 * sur son critere.
 */
@Service
public class LeadRoutingService {

    private static final Logger log = LoggerFactory.getLogger(LeadRoutingService.class);

    private final LeadRepository leadRepository;
    private final ClientRepository clientRepository;
    private final SalesRepRepository salesRepRepository;
    private final RotationOrder rotation;
    private final AssignmentStrategyRegistry registre;
    private final RoutedLeadWriter writer;
    private final RoutedLeadPublisher publisher;

    public LeadRoutingService(
            LeadRepository leadRepository,
            ClientRepository clientRepository,
            SalesRepRepository salesRepRepository,
            RotationOrder rotation,
            AssignmentStrategyRegistry registre,
            RoutedLeadWriter writer,
            RoutedLeadPublisher publisher) {
        this.leadRepository = leadRepository;
        this.clientRepository = clientRepository;
        this.salesRepRepository = salesRepRepository;
        this.rotation = rotation;
        this.registre = registre;
        this.writer = writer;
        this.publisher = publisher;
    }

    /**
     * @return le lead attribue ; vide quand il n'y a rien a router — lead introuvable. Le
     *     message est alors acquitte : cet echec est deterministe et n'a rien a faire en DLQ.
     * @throws AssignmentException si le client n'a aucun commercial actif
     */
    public Optional<Lead> route(UUID leadId) {
        Lead lead = leadRepository.findById(leadId).orElse(null);
        if (lead == null) {
            log.warn("Lead {} introuvable : rien a router", leadId);
            return Optional.empty();
        }
        if (lead.getAssignedSalesRepId() != null) {
            // Rejeu : ne pas reattribuer, sous peine de decaler la rotation. Republier, en
            // revanche, est necessaire — un message perdu entre les deux etapes laisserait
            // un lead ROUTED que plus rien ne synchroniserait.
            publisher.publie(lead);
            return Optional.of(lead);
        }

        Client client = clientRepository.findById(lead.getClientId())
                .orElseThrow(() -> new IllegalStateException(
                        "Le lead " + leadId + " reference un client inexistant"));

        List<SalesRep> actifs = salesRepRepository.findByClientIdAndActiveTrue(lead.getClientId());
        if (actifs.isEmpty()) {
            throw new AssignmentException(lead.getClientId(), leadId);
        }

        List<SalesRep> ordonnes = rotation.parAnciennete(lead.getClientId(), actifs);
        SalesRep choisi = registre.pour(client.getAssignmentStrategy())
                .choisit(lead, ordonnes)
                .orElseThrow(() -> new AssignmentException(lead.getClientId(), leadId));
        journaliseLeRepli(client, lead, choisi);

        Lead route = writer.attribue(leadId, choisi.getId());
        publisher.publie(route);
        return Optional.of(route);
    }

    /**
     * Le repli est silencieux pour le prospect, il ne doit pas l'etre pour l'agence : sans
     * cette trace, un client dont aucun commercial ne porte de zone verrait toutes ses
     * attributions passer par le tour de role sans que rien ne signale la configuration
     * incomplete.
     */
    private void journaliseLeRepli(Client client, Lead lead, SalesRep choisi) {
        boolean repli = switch (client.getAssignmentStrategy()) {
            case GEOGRAPHIC ->
                    !GeographicStrategy.correspond(choisi.getZone(), lead.getCountryCode());
            case SECTOR -> !GeographicStrategy.correspond(choisi.getSector(), lead.getSector());
            case ROUND_ROBIN -> false;
        };
        if (repli) {
            log.info("Strategie {} sans correspondance pour le lead {} : repli sur le tour de role",
                    client.getAssignmentStrategy(), lead.getId());
        }
    }
}
