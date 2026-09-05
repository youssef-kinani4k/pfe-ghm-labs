import { Component, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { TransitionSecret } from '../../../core/models/tenant';

/**
 * Le bandeau d'une rotation en cours : jusqu'a quand l'ancien secret vaut, et si la
 * boutique a fini de migrer.
 *
 * Le bouton de revocation vit ici et nulle part ailleurs — hors transition, il n'aurait
 * rien a revoquer. Presentationnel : aucun appel HTTP, comme `secret-revele`.
 */
@Component({
  selector: 'app-bandeau-transition',
  imports: [DatePipe, MatButtonModule, MatIconModule],
  templateUrl: './bandeau-transition.html',
  styleUrl: './bandeau-transition.scss',
})
export class BandeauTransition {
  readonly transition = input.required<TransitionSecret>();

  /** Emis au clic : c'est la fiche qui appelle l'API et rafraichit. */
  readonly revoque = output<void>();
}
