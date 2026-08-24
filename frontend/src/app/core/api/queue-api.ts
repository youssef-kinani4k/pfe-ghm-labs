import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { QueuesView } from '../models/monitoring';

/**
 * Profondeur et consommateurs des files, lus en AMQP par le backend.
 *
 * Aucun parametre : l'etat des files est global a l'instance, il n'y a rien a filtrer.
 */
@Injectable({ providedIn: 'root' })
export class QueueApi {
  private readonly http = inject(HttpClient);

  files() {
    return this.http.get<QueuesView>('/api/queues');
  }
}
