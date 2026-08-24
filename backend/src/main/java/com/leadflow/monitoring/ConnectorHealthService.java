package com.leadflow.monitoring;

import com.leadflow.config.CrmProperties;
import com.leadflow.crm.CrmConnector;
import com.leadflow.crm.CrmSyncAttempt;
import com.leadflow.crm.CrmSyncAttemptStatus;
import com.leadflow.monitoring.dto.ConnectorClientActivity;
import com.leadflow.monitoring.dto.ConnectorView;
import com.leadflow.tenant.Client;
import com.leadflow.tenant.ClientRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fusionne deux sources : les fournisseurs <b>declares</b> — connecteurs presents dans le
 * classpath, plus le drapeau {@code enabled} de la configuration — et leur <b>activite
 * reelle</b>, agregee depuis la trace.
 *
 * <p>Un fournisseur declare mais sans trace apparait avec des compteurs a zero : l'absence
 * d'activite est une information, et la taire donnerait un ecran ou un ERP jamais appele
 * ressemble a un ERP absent.
 *
 * <p>La liste des connecteurs est injectee en {@code List<CrmConnector>} plutot que lue
 * dans {@code CrmConnectorRegistry.availableProviders()} : ce dernier filtre deja les
 * desactives, or c'est precisement la distinction que l'ecran doit montrer.
 */
@Service
public class ConnectorHealthService {

    private final List<CrmConnector> connecteurs;
    private final CrmProperties properties;
    private final SyncActivityRepository activite;
    private final ClientRepository clients;

    public ConnectorHealthService(
            List<CrmConnector> connecteurs,
            CrmProperties properties,
            SyncActivityRepository activite,
            ClientRepository clients) {
        this.connecteurs = connecteurs;
        this.properties = properties;
        this.activite = activite;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<ConnectorView> etatDesConnecteurs() {
        Set<String> implementes = connecteurs.stream()
                .map(CrmConnector::providerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, CrmProperties.Provider> declares =
                properties.providers() == null ? Map.of() : properties.providers();

        // Union des deux sources : un connecteur present sans configuration, comme une
        // configuration sans connecteur, sont deux anomalies que l'ecran doit montrer.
        Set<String> tous = new LinkedHashSet<>(implementes);
        tous.addAll(declares.keySet());

        Map<String, SyncActivityRepository.ActiviteParFournisseur> parFournisseur =
                activite.activiteParFournisseur().stream()
                        .collect(Collectors.toMap(
                                SyncActivityRepository.ActiviteParFournisseur::getProviderId,
                                ligne -> ligne));
        Map<UUID, String> nomsDeClient = clients.findAll().stream()
                .collect(Collectors.toMap(Client::getId, Client::getName));
        Map<String, List<ConnectorClientActivity>> detail = detailParFournisseur(nomsDeClient);

        List<ConnectorView> vues = new ArrayList<>();
        for (String providerId : tous) {
            SyncActivityRepository.ActiviteParFournisseur ligne = parFournisseur.get(providerId);
            CrmProperties.Provider reglages = declares.get(providerId);

            vues.add(new ConnectorView(
                    providerId,
                    implementes.contains(providerId),
                    reglages != null && reglages.enabled(),
                    ligne == null ? 0L : ligne.getSucces(),
                    ligne == null ? 0L : ligne.getEchecs(),
                    ligne == null ? null : ligne.getDernierSucces(),
                    ligne == null ? null : ligne.getDernierEchec(),
                    dernierMessageDEchec(providerId),
                    detail.getOrDefault(providerId, List.of())));
        }
        vues.sort(Comparator.comparing(ConnectorView::providerId));
        return vues;
    }

    private Map<String, List<ConnectorClientActivity>> detailParFournisseur(
            Map<UUID, String> nomsDeClient) {
        return activite.activiteParClient().stream()
                .collect(Collectors.groupingBy(
                        SyncActivityRepository.ActiviteParClient::getProviderId,
                        Collectors.mapping(
                                ligne -> new ConnectorClientActivity(
                                        ligne.getClientId(),
                                        nomsDeClient.get(ligne.getClientId()),
                                        ligne.getSucces(),
                                        ligne.getEchecs(),
                                        ligne.getDerniereTentative()),
                                Collectors.toList())));
    }

    private String dernierMessageDEchec(String providerId) {
        return activite
                .findTop1ByProviderIdAndStatusOrderByAttemptedAtDesc(
                        providerId, CrmSyncAttemptStatus.FAILED)
                .stream()
                .findFirst()
                .map(CrmSyncAttempt::getErrorMessage)
                .orElse(null);
    }
}
