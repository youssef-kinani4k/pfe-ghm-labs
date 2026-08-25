package com.leadflow.tenant;

import com.leadflow.crm.CrmConnector;
import com.leadflow.crm.CrmConnectorRegistry;
import com.leadflow.crm.model.CrmCheck;
import com.leadflow.crm.model.CrmTarget;
import com.leadflow.tenant.dto.CrmProviderView;
import com.leadflow.tenant.dto.CrmTestRequest;
import com.leadflow.tenant.dto.CrmTestResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ce que le formulaire de boutique a besoin de savoir sur les ERP disponibles.
 *
 * <p>Le controleur vit dans {@code tenant/} bien qu'il parle d'ERP : il sert l'ecran
 * d'administration des boutiques, et {@code crm/} n'expose aucune route — c'est un port de
 * sortie, pas une facade HTTP.
 */
@RestController
@RequestMapping("/api/admin/crm")
public class CrmProviderController {

    private final CrmConnectorRegistry registre;

    public CrmProviderController(CrmConnectorRegistry registre) {
        this.registre = registre;
    }

    @GetMapping("/providers")
    public List<CrmProviderView> fournisseurs() {
        return registre.availableProviders().stream()
                .sorted()
                .map(id -> new CrmProviderView(id, registre.forProvider(id).reglagesAttendus()))
                .toList();
    }

    @PostMapping("/test")
    public CrmTestResult teste(@Valid @RequestBody CrmTestRequest demande) {
        CrmConnector connecteur;
        try {
            connecteur = registre.forProvider(demande.crmProviderId());
        } catch (IllegalArgumentException inconnu) {
            throw new ReglageManquantException(inconnu.getMessage());
        }
        CrmCheck resultat = connecteur.verifieAcces(
                new CrmTarget(demande.crmProviderId(), demande.crmSettings()));
        return new CrmTestResult(resultat.ok(), resultat.cause().name(), resultat.detail());
    }
}
