import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { LeadDetail, LeadSummary } from '../models/lead';
import { PageResponse } from '../models/page-response';
import { IntentSource, LeadStatus } from '../models/monitoring';

export interface LeadQuery {
  page: number;
  size: number;
  sort: string;
  clientId?: string;
  status?: LeadStatus[];
  intent?: string;
  intentSource?: IntentSource;
  salesRepId?: string;
  minScore?: number;
  from?: string;
  to?: string;
  q?: string;
}

/**
 * Acces aux leads.
 *
 * Chemins relatifs, jamais d'URL absolue : environment.apiBaseUrl est vide dans les deux
 * environnements — en dev le proxy renvoie /api vers :8090, en production le dashboard est
 * servi derriere le meme domaine que l'API.
 */
@Injectable({ providedIn: 'root' })
export class LeadApi {
  private readonly http = inject(HttpClient);

  liste(requete: LeadQuery) {
    let params = new HttpParams()
      .set('page', requete.page)
      .set('size', requete.size)
      .set('sort', requete.sort);

    // Un filtre absent ne doit pas partir : cote serveur, chaque parametre present ajoute
    // un predicat, et un « q » vide ferait un LIKE '%%' sur toute la table. Un tableau vide
    // se traite comme une absence — « aucun statut coche » veut dire « tous ».
    for (const [cle, valeur] of Object.entries(requete)) {
      if (
        ['page', 'size', 'sort'].includes(cle) ||
        valeur === undefined ||
        valeur === null ||
        valeur === ''
      ) {
        continue;
      }
      if (Array.isArray(valeur)) {
        valeur.forEach((element) => (params = params.append(cle, String(element))));
      } else {
        params = params.set(cle, String(valeur));
      }
    }
    return this.http.get<PageResponse<LeadSummary>>('/api/leads', { params });
  }

  detail(id: string) {
    return this.http.get<LeadDetail>(`/api/leads/${id}`);
  }
}
