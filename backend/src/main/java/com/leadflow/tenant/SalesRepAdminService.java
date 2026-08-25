package com.leadflow.tenant;

import com.leadflow.common.RessourceIntrouvableException;
import com.leadflow.tenant.dto.SalesRepAdminView;
import com.leadflow.tenant.dto.SalesRepForm;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commerciaux d'une boutique.
 *
 * <p>La regle du dernier commercial actif est gardee <b>ici</b> et pas seulement a l'ecran :
 * l'API est appelable directement, et cette regle protege le pipeline, pas le confort de
 * l'interface.
 */
@Service
public class SalesRepAdminService {

    private final SalesRepRepository commerciaux;
    private final ClientRepository clients;

    public SalesRepAdminService(SalesRepRepository commerciaux, ClientRepository clients) {
        this.commerciaux = commerciaux;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    public List<SalesRepAdminView> deLaBoutique(UUID clientId) {
        return commerciaux.findByClientIdOrderByFullName(clientId).stream()
                .map(this::vue)
                .toList();
    }

    @Transactional
    public SalesRepAdminView ajoute(UUID clientId, SalesRepForm formulaire) {
        Client client = clients.findById(clientId)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucune boutique avec cet identifiant"));
        SalesRep rep = new SalesRep();
        rep.setClient(client);
        applique(rep, formulaire);
        rep.setActive(true);
        return vue(commerciaux.save(rep));
    }

    @Transactional
    public SalesRepAdminView metAJour(UUID id, SalesRepForm formulaire) {
        SalesRep rep = trouve(id);
        applique(rep, formulaire);
        return vue(rep);
    }

    /**
     * Active ou desactive un commercial.
     *
     * <p>Le refus ne vaut que pour une boutique active : fermee, elle ne capture plus rien,
     * donc son dernier commercial peut partir sans mettre de lead en peril.
     */
    @Transactional
    public SalesRepAdminView change(UUID id, boolean actif) {
        SalesRep rep = trouve(id);
        if (!actif && rep.isActive()) {
            UUID clientId = rep.getClient().getId();
            boolean boutiqueActive = rep.getClient().isActive();
            long actifs = commerciaux.countByClientIdAndActiveTrue(clientId);
            if (boutiqueActive && actifs <= 1) {
                throw new DernierCommercialException(
                        "C'est le dernier commercial actif de cette boutique. Sans lui, ses "
                                + "leads partiraient en file d'echec : ajouter un remplacant "
                                + "d'abord, ou desactiver la boutique.");
            }
        }
        rep.setActive(actif);
        return vue(rep);
    }

    private SalesRep trouve(UUID id) {
        return commerciaux.findById(id)
                .orElseThrow(() -> new RessourceIntrouvableException(
                        "Aucun commercial avec cet identifiant"));
    }

    private void applique(SalesRep rep, SalesRepForm formulaire) {
        rep.setFullName(formulaire.fullName());
        rep.setEmail(formulaire.email());
        rep.setSector(formulaire.sector());
        rep.setZone(formulaire.zone());
        rep.setCrmRef(formulaire.crmRef());
    }

    private SalesRepAdminView vue(SalesRep rep) {
        return new SalesRepAdminView(
                rep.getId(), rep.getFullName(), rep.getEmail(),
                rep.getSector(), rep.getZone(), rep.getCrmRef(), rep.isActive());
    }
}
