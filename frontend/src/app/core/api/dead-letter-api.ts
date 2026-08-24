import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { DeadLetterStatus, DeadLetterView } from '../models/monitoring';
import { PageResponse } from '../models/page-response';

export interface DeadLetterQuery {
  page: number;
  size: number;
  status?: DeadLetterStatus;
  originQueue?: string;
  clientId?: string;
  from?: string;
  to?: string;
}

/**
 * Journal des messages morts.
 *
 * Le rejeu et la mise a l'ecart sont unitaires, et il n'y a volontairement pas d'endpoint de
 * masse : rejouer en bloc un incident qu'on n'a pas compris le multiplie. Une selection
 * multiple a l'ecran boucle sur ces appels-la.
 */
@Injectable({ providedIn: 'root' })
export class DeadLetterApi {
  private readonly http = inject(HttpClient);

  liste(requete: DeadLetterQuery) {
    let params = new HttpParams().set('page', requete.page).set('size', requete.size);
    for (const [cle, valeur] of Object.entries(requete)) {
      if (
        !['page', 'size'].includes(cle) &&
        valeur !== undefined &&
        valeur !== null &&
        valeur !== ''
      ) {
        params = params.set(cle, String(valeur));
      }
    }
    return this.http.get<PageResponse<DeadLetterView>>('/api/dead-letters', { params });
  }

  rejoue(id: string) {
    return this.http.post<void>(`/api/dead-letters/${id}/replay`, {});
  }

  ecarte(id: string) {
    return this.http.post<void>(`/api/dead-letters/${id}/discard`, {});
  }
}
