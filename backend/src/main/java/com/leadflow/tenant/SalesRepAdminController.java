package com.leadflow.tenant;

import com.leadflow.tenant.dto.SalesRepAdminView;
import com.leadflow.tenant.dto.SalesRepForm;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actions sur un commercial deja cree.
 *
 * <p>Elles ne sont pas imbriquees sous la boutique : un commercial se designe par son seul
 * identifiant, et rappeler sa boutique dans l'URL ouvrirait la question de ce qu'il faut
 * faire quand les deux ne concordent pas. La creation, elle, reste imbriquee — c'est la
 * boutique qui recrute.
 */
@RestController
@RequestMapping("/api/admin/sales-reps")
public class SalesRepAdminController {

    private final SalesRepAdminService service;

    public SalesRepAdminController(SalesRepAdminService service) {
        this.service = service;
    }

    @PutMapping("/{id}")
    public SalesRepAdminView metAJour(
            @PathVariable UUID id, @Valid @RequestBody SalesRepForm formulaire) {
        return service.metAJour(id, formulaire);
    }

    @PostMapping("/{id}/activate")
    public SalesRepAdminView active(@PathVariable UUID id) {
        return service.change(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public SalesRepAdminView desactive(@PathVariable UUID id) {
        return service.change(id, false);
    }
}
