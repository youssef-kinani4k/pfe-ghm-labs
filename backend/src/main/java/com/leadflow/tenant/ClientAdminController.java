package com.leadflow.tenant;

import com.leadflow.tenant.dto.ClientCreated;
import com.leadflow.tenant.dto.ClientDetailAdmin;
import com.leadflow.tenant.dto.ClientForm;
import com.leadflow.tenant.dto.ClientSummaryAdmin;
import com.leadflow.tenant.dto.SalesRepAdminView;
import com.leadflow.tenant.dto.SalesRepForm;
import com.leadflow.tenant.dto.SecretRotated;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration des boutiques, sous {@code /api/admin} pour ne pas entrer en collision avec
 * {@code /api/clients}, l'annuaire en lecture seule de {@code monitoring/} qui alimente les
 * filtres du dashboard. Les deux coexistent : ils servent deux besoins distincts.
 */
@RestController
@RequestMapping("/api/admin/clients")
public class ClientAdminController {

    private final ClientAdminService service;
    private final SalesRepAdminService commerciaux;

    public ClientAdminController(
            ClientAdminService service, SalesRepAdminService commerciaux) {
        this.service = service;
        this.commerciaux = commerciaux;
    }

    @GetMapping
    public List<ClientSummaryAdmin> liste() {
        return service.liste();
    }

    @GetMapping("/{id}")
    public ClientDetailAdmin fiche(@PathVariable UUID id) {
        return service.fiche(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClientCreated cree(@Valid @RequestBody ClientForm formulaire) {
        return service.cree(formulaire);
    }

    @PutMapping("/{id}")
    public ClientDetailAdmin metAJour(@PathVariable UUID id, @Valid @RequestBody ClientForm f) {
        return service.metAJour(id, f);
    }

    /**
     * L'activation et la desactivation sont des sous-ressources et non un champ du formulaire :
     * couper la capture d'une boutique ne doit pas pouvoir arriver par une correction de nom.
     */
    @PostMapping("/{id}/activate")
    public ClientDetailAdmin active(@PathVariable UUID id) {
        return service.change(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public ClientDetailAdmin desactive(@PathVariable UUID id) {
        return service.change(id, false);
    }

    /**
     * Le secret tourne est rendu par cette seule reponse : ni la fiche ni la liste ne le
     * portent, donc l'ecran doit le faire copier maintenant ou le perdre.
     */
    @PostMapping("/{id}/rotate-secret")
    public SecretRotated tourneLeSecret(@PathVariable UUID id) {
        return service.tourneLeSecret(id);
    }

    /**
     * Ferme la fenetre de transition ouverte par la derniere rotation. Un POST sur une
     * sous-ressource nommee par son geste, comme {@code rotate-secret} et
     * {@code deactivate} : c'est la convention de ce controleur.
     */
    @PostMapping("/{id}/revoke-previous-secret")
    public ClientDetailAdmin revoqueLeSecretPrecedent(@PathVariable UUID id) {
        return service.revoqueLeSecretPrecedent(id);
    }

    @PostMapping("/{id}/rotate-public-key")
    public ClientDetailAdmin tourneLaClePublique(@PathVariable UUID id) {
        return service.tourneLaClePublique(id);
    }

    @GetMapping("/{id}/sales-reps")
    public List<SalesRepAdminView> commerciaux(@PathVariable UUID id) {
        return commerciaux.deLaBoutique(id);
    }

    /** Imbriquee sous la boutique, parce que c'est elle qui recrute. */
    @PostMapping("/{id}/sales-reps")
    @ResponseStatus(HttpStatus.CREATED)
    public SalesRepAdminView ajoute(
            @PathVariable UUID id, @Valid @RequestBody SalesRepForm formulaire) {
        return commerciaux.ajoute(id, formulaire);
    }
}
