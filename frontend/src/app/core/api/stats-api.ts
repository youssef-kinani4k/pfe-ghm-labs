import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { SeriesView, StatsView } from '../models/monitoring';

/**
 * Agregats du dashboard.
 *
 * Meme regle que LeadApi : un parametre absent ne part pas, sans quoi le serveur ajouterait
 * un predicat pour un filtre que personne n'a demande.
 */
@Injectable({ providedIn: 'root' })
export class StatsApi {
  private readonly http = inject(HttpClient);

  stats(clientId?: string, from?: string, to?: string) {
    let params = new HttpParams();
    for (const [cle, valeur] of Object.entries({ clientId, from, to })) {
      if (valeur !== undefined && valeur !== null && valeur !== '') {
        params = params.set(cle, valeur);
      }
    }
    return this.http.get<StatsView>('/api/stats', { params });
  }

  /** Les trois series quotidiennes. `jours` vaut 7, 30 ou 90 — le serveur refuse le reste. */
  series(clientId: string | undefined, jours: number) {
    let params = new HttpParams().set('jours', String(jours));
    if (clientId !== undefined && clientId !== null && clientId !== '') {
      params = params.set('clientId', clientId);
    }
    return this.http.get<SeriesView>('/api/stats/series', { params });
  }
}
