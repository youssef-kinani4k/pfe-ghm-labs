import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { DiagnosticNotification } from '../models/notification';

/**
 * Diagnostic du canal de notification.
 *
 * Il n'y a rien a enregistrer ici, contrairement a la cle d'analyse d'intention : les
 * reglages du relais sont globaux a l'instance et viennent de l'environnement. Ce que
 * l'operateur peut faire, c'est verifier qu'ils fonctionnent.
 */
@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly http = inject(HttpClient);

  /** Envoie un vrai message d'essai a l'adresse donnee. */
  teste(destinataire: string) {
    return this.http.post<DiagnosticNotification>('/api/admin/notification/test', {
      destinataire,
    });
  }
}
