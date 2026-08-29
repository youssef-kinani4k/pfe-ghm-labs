package com.leadflow.tenant;

import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.qualification.ScoringConfig;
import com.leadflow.tenant.dto.ScoringForm;
import com.leadflow.tenant.dto.ScoringView;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglage du bareme d'une boutique.
 *
 * <p>Ce service importe {@code ScoringConfig} de {@code qualification/}, alors que
 * {@code qualification/} importe deja {@code tenant/} : le cycle entre les deux packages est
 * assume. L'alternative — redeclarer les noms de cles ici — garantissait une derive le jour
 * ou une cle change. C'est {@code ScoringAllerRetourTest} qui tient la garantie.
 */
@Service
public class ScoringAdminService {

    private final ClientRepository clients;

    public ScoringAdminService(ClientRepository clients) {
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public ScoringView lit(UUID id) {
        return ScoringView.de(ScoringConfig.depuis(trouve(id).getScoringConfig()));
    }

    /**
     * Remplace le document entier. Pas de fusion partielle : elle rendrait indecidable la
     * difference entre « poids absent » et « poids remis a zero ».
     */
    @Transactional
    public ScoringView remplace(UUID id, ScoringForm formulaire) {
        Client client = trouve(id);
        client.setScoringConfig(formulaire.versDocument());
        return ScoringView.de(ScoringConfig.depuis(client.getScoringConfig()));
    }

    private Client trouve(UUID id) {
        return clients.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
    }
}
