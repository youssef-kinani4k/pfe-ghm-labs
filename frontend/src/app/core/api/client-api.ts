import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ClientSummary, SalesRepSummary } from '../models/monitoring';

/**
 * Annuaire des clients et de leurs commerciaux.
 *
 * Il alimente les listes deroulantes de filtre. Aucun secret ne transite : le backend rend
 * des `record` qui laissent `hmac_secret` et `crm_config` de cote.
 */
@Injectable({ providedIn: 'root' })
export class ClientApi {
  private readonly http = inject(HttpClient);

  clients() {
    return this.http.get<ClientSummary[]>('/api/clients');
  }

  commerciaux(clientId: string) {
    return this.http.get<SalesRepSummary[]>(`/api/clients/${clientId}/sales-reps`);
  }
}
