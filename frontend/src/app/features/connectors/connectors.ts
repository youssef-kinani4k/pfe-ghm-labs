import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ConnectorApi } from '../../core/api/connector-api';
import { ConnectorView } from '../../core/models/monitoring';

/** Les etats qu'un connecteur peut presenter, du plus grave au plus banal. */
export type EtatConnecteur = 'anomalie' | 'echec' | 'desactive' | 'inactif' | 'sain';

/**
 * Etat des connecteurs ERP.
 *
 * Trois situations ne doivent surtout pas se confondre : desactive par configuration, actif
 * mais sans aucune trace, et actif dont la derniere tentative a echoue. La derniere est la
 * seule qui appelle une intervention, et la donner a voir comme les deux autres reviendrait
 * a cacher une panne.
 */
@Component({
  selector: 'app-connectors',
  imports: [
    DatePipe,
    MatCardModule,
    MatExpansionModule,
    MatIconModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  templateUrl: './connectors.html',
  styleUrl: './connectors.scss',
})
export class Connectors implements OnInit {
  private readonly api = inject(ConnectorApi);

  readonly connecteurs = signal<ConnectorView[]>([]);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.charge();
  }

  charge(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.api.connecteurs().subscribe({
      next: (liste) => {
        this.connecteurs.set(liste);
        this.enCours.set(false);
      },
      error: () => {
        this.erreur.set('L etat des connecteurs n a pas pu etre charge.');
        this.enCours.set(false);
      },
    });
  }

  /**
   * Un fournisseur configure sans adaptateur present — ou l'inverse — est une anomalie de
   * deploiement : elle passe avant tout le reste, parce qu'aucun compteur ne la revelerait.
   */
  etat(connecteur: ConnectorView): EtatConnecteur {
    if (connecteur.enabled && !connecteur.implemented) {
      return 'anomalie';
    }
    if (!connecteur.enabled) {
      return 'desactive';
    }
    if (this.dernierAppelEnEchec(connecteur)) {
      return 'echec';
    }
    if (connecteur.successCount === 0 && connecteur.failureCount === 0) {
      return 'inactif';
    }
    return 'sain';
  }

  /**
   * « Dernier appel en echec » se lit en comparant les deux horodatages, pas en regardant
   * le seul compteur d'echecs : un connecteur qui a echoue hier et reussi depuis va bien.
   */
  private dernierAppelEnEchec(connecteur: ConnectorView): boolean {
    if (!connecteur.lastFailureAt) {
      return false;
    }
    return !connecteur.lastSuccessAt || connecteur.lastFailureAt > connecteur.lastSuccessAt;
  }

  libelle(etat: EtatConnecteur): string {
    return {
      anomalie: 'Anomalie de deploiement',
      echec: 'Dernier appel en echec',
      desactive: 'Desactive par configuration',
      inactif: 'Aucune synchronisation a ce jour',
      sain: 'Operationnel',
    }[etat];
  }

  icone(etat: EtatConnecteur): string {
    return {
      anomalie: 'report',
      echec: 'error',
      desactive: 'toggle_off',
      inactif: 'schedule',
      sain: 'check_circle',
    }[etat];
  }
}
