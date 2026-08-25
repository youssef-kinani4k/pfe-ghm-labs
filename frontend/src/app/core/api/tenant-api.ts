import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  ClientCreated,
  ClientDetailAdmin,
  ClientForm,
  ClientSummaryAdmin,
  CrmProviderView,
  CrmTestResult,
  SalesRepAdminView,
  SalesRepForm,
  SecretRotated,
} from '../models/tenant';

/**
 * Administration des boutiques.
 *
 * Distinct de ClientApi, qui lit l'annuaire de monitoring pour alimenter les filtres :
 * celui-ci ecrit, et vise /api/admin. Les deux coexistent parce que le backend les separe.
 *
 * Chemins relatifs, jamais d'URL absolue : environment.apiBaseUrl est vide dans les deux
 * environnements.
 */
@Injectable({ providedIn: 'root' })
export class TenantApi {
  private readonly http = inject(HttpClient);

  boutiques() {
    return this.http.get<ClientSummaryAdmin[]>('/api/admin/clients');
  }

  boutique(id: string) {
    return this.http.get<ClientDetailAdmin>(`/api/admin/clients/${id}`);
  }

  cree(formulaire: ClientForm) {
    return this.http.post<ClientCreated>('/api/admin/clients', formulaire);
  }

  metAJour(id: string, formulaire: ClientForm) {
    return this.http.put<ClientDetailAdmin>(`/api/admin/clients/${id}`, formulaire);
  }

  // Activation et desactivation sont des sous-ressources et non un champ du PUT : la
  // desactivation arrete la capture, une consequence qui merite un geste explicite.
  active(id: string) {
    return this.http.post<ClientDetailAdmin>(`/api/admin/clients/${id}/activate`, {});
  }

  desactive(id: string) {
    return this.http.post<ClientDetailAdmin>(`/api/admin/clients/${id}/deactivate`, {});
  }

  tourneLeSecret(id: string) {
    return this.http.post<SecretRotated>(`/api/admin/clients/${id}/rotate-secret`, {});
  }

  tourneLaClePublique(id: string) {
    return this.http.post<ClientDetailAdmin>(`/api/admin/clients/${id}/rotate-public-key`, {});
  }

  commerciaux(clientId: string) {
    return this.http.get<SalesRepAdminView[]>(`/api/admin/clients/${clientId}/sales-reps`);
  }

  ajouteUnCommercial(clientId: string, formulaire: SalesRepForm) {
    return this.http.post<SalesRepAdminView>(
      `/api/admin/clients/${clientId}/sales-reps`,
      formulaire,
    );
  }

  // Les commerciaux ont leur propre racine une fois crees : leur identifiant suffit a les
  // designer, et repeter la boutique dans l'URL ouvrirait la porte a une incoherence.
  metAJourUnCommercial(id: string, formulaire: SalesRepForm) {
    return this.http.put<SalesRepAdminView>(`/api/admin/sales-reps/${id}`, formulaire);
  }

  activeUnCommercial(id: string) {
    return this.http.post<SalesRepAdminView>(`/api/admin/sales-reps/${id}/activate`, {});
  }

  desactiveUnCommercial(id: string) {
    return this.http.post<SalesRepAdminView>(`/api/admin/sales-reps/${id}/deactivate`, {});
  }

  fournisseurs() {
    return this.http.get<CrmProviderView[]>('/api/admin/crm/providers');
  }

  /** Eprouve des reglages sans les enregistrer : tout l'interet est de verifier avant de sauver. */
  teste(providerId: string, reglages: Record<string, string>) {
    return this.http.post<CrmTestResult>('/api/admin/crm/test', {
      crmProviderId: providerId,
      crmSettings: reglages,
    });
  }
}
