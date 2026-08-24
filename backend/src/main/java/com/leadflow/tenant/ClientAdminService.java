package com.leadflow.tenant;

import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.crm.CrmConnectorRegistry;
import com.leadflow.crm.model.CrmSettingSpec;
import com.leadflow.tenant.dto.ClientDetailAdmin;
import com.leadflow.tenant.dto.ClientSummaryAdmin;
import com.leadflow.tenant.dto.SalesRepAdminView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration des boutiques.
 *
 * <p>Ce service vit dans {@code tenant/} et non dans {@code monitoring/} : il ecrit dans
 * {@code client} et {@code sales_rep}, alors que {@code monitoring/} est un observateur qui
 * n'ecrit que sa table {@code dead_letter}.
 *
 * <p>Il ne chiffre rien lui-meme : {@code EncryptedStringConverter} et
 * {@code EncryptedJsonConverter} s'en chargent a l'ecriture. Le service manipule du clair.
 */
@Service
public class ClientAdminService {

    private final ClientRepository clients;
    private final SalesRepRepository commerciaux;
    private final CrmConnectorRegistry connecteurs;

    public ClientAdminService(
            ClientRepository clients,
            SalesRepRepository commerciaux,
            CrmConnectorRegistry connecteurs) {
        this.clients = clients;
        this.commerciaux = commerciaux;
        this.connecteurs = connecteurs;
    }

    @Transactional(readOnly = true)
    public List<ClientSummaryAdmin> liste() {
        return clients.findAllByOrderByNameAsc().stream()
                .map(client -> new ClientSummaryAdmin(
                        client.getId(),
                        client.getName(),
                        client.getCrmProviderId(),
                        client.getAssignmentStrategy(),
                        client.isActive(),
                        commerciaux.countByClientIdAndActiveTrue(client.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientDetailAdmin fiche(UUID id) {
        Client client = clients.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
        return new ClientDetailAdmin(
                client.getId(),
                client.getName(),
                client.getPublicKey(),
                "/api/webhooks/leads/" + client.getPublicKey(),
                client.getCrmProviderId(),
                reglagesNonSecrets(client),
                client.getAssignmentStrategy(),
                client.isActive(),
                commerciaux.findByClientIdOrderByFullName(client.getId()).stream()
                        .map(this::vue)
                        .toList());
    }

    /**
     * Ne rend que ce qui n'est pas secret.
     *
     * <p>La liste des cles secretes vient du connecteur, jamais d'un {@code switch} ecrit
     * ici : {@code tenant/} ne doit rien savoir de Dolibarr ni d'Odoo. Un fournisseur dont
     * le connecteur a disparu de la configuration rend une carte vide plutot que d'echouer —
     * la fiche doit rester consultable pour qu'on puisse corriger le fournisseur.
     */
    private Map<String, String> reglagesNonSecrets(Client client) {
        Set<String> secretes;
        try {
            secretes = connecteurs.forProvider(client.getCrmProviderId()).reglagesAttendus()
                    .stream()
                    .filter(CrmSettingSpec::secret)
                    .map(CrmSettingSpec::cle)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException fournisseurInconnu) {
            return Map.of();
        }
        Map<String, String> visibles = new LinkedHashMap<>();
        client.getCrmConfig().forEach((cle, valeur) -> {
            if (!secretes.contains(cle)) {
                visibles.put(cle, valeur);
            }
        });
        return visibles;
    }

    private SalesRepAdminView vue(SalesRep rep) {
        return new SalesRepAdminView(
                rep.getId(), rep.getFullName(), rep.getEmail(),
                rep.getSector(), rep.getZone(), rep.getCrmRef(), rep.isActive());
    }
}
