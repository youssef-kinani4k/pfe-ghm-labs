import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ConnectorView } from '../models/monitoring';

/**
 * Etat des connecteurs ERP, derive des traces de synchronisation.
 *
 * Le backend ne contacte aucun ERP pour rendre cette vue : elle se lit dans
 * `crm_sync_attempt`. Un ecran de supervision qui interrogerait les ERP a chaque affichage
 * ferait dependre son propre temps de reponse de la disponibilite de chacun d'eux.
 */
@Injectable({ providedIn: 'root' })
export class ConnectorApi {
  private readonly http = inject(HttpClient);

  connecteurs() {
    return this.http.get<ConnectorView[]>('/api/connectors');
  }
}
