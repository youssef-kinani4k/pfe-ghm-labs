import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { EtatIntent, IntentForm, IntentTestResult } from '../models/intent';

/**
 * Reglage de l'analyse d'intention : cle d'API, interrupteur, diagnostic.
 *
 * La cle ne fait qu'un aller : elle part vers le serveur et n'en revient jamais. Ce service
 * ne la conserve donc nulle part, et l'ecran ne peut pas la reafficher — c'est voulu.
 */
@Injectable({ providedIn: 'root' })
export class IntentApi {
  private readonly http = inject(HttpClient);

  etat() {
    return this.http.get<EtatIntent>('/api/admin/intent');
  }

  /**
   * Une cle vide n'est pas transmise : le backend conserve alors celle deja enregistree.
   * L'envoyer serait le seul moyen de perdre la cle en actionnant l'interrupteur.
   */
  enregistre(formulaire: IntentForm) {
    const corps: IntentForm = { actif: formulaire.actif };
    if (formulaire.apiKey && formulaire.apiKey.trim().length > 0) {
      corps.apiKey = formulaire.apiKey.trim();
    }
    return this.http.put<EtatIntent>('/api/admin/intent', corps);
  }

  /** Eprouve une cle sans la mettre en service. Sans argument, eprouve celle en place. */
  teste(apiKey?: string) {
    return this.http.post<IntentTestResult>('/api/admin/intent/test', { apiKey });
  }
}
