import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { LeadDetail, LeadSummary, ReassignmentForm, TimelineEntry } from '../models/lead';
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
  /**
   * `true` et non `boolean` : la boucle ci-dessous ne saute que `undefined`, `null` et la
   * chaine vide, donc un `false` partirait dans l'URL et ajouterait un predicat cote
   * serveur. Le filtre n'a pas de negation — « pas seulement les chauds » veut dire « tous »,
   * ce qui est l'absence du parametre. La case decochee doit donc valoir `undefined`.
   */
  chaud?: true;
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

  /**
   * Endpoint separe du detail : F10 devra rafraichir la seule chronologie apres une
   * reattribution, sans refaire tout le detail.
   */
  timeline(id: string) {
    return this.http.get<TimelineEntry[]>(`/api/leads/${id}/timeline`);
  }

  /**
   * Reattribution manuelle. Le backend rend la fiche rechargee : l'ecran affiche donc l'etat
   * reellement enregistre, sans un aller-retour de plus qui pourrait montrer autre chose.
   */
  reattribue(id: string, formulaire: ReassignmentForm) {
    return this.http.post<LeadDetail>(`/api/leads/${id}/reassign`, formulaire);
  }
}
