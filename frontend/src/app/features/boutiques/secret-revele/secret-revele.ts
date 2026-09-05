import { Component, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, DOCUMENT } from '@angular/common';

/**
 * Le secret HMAC, montre une seule fois.
 *
 * Le backend ne sait pas le redonner : il est chiffre au repos et n'est rendu qu'a la
 * creation et a la rotation. L'ecran ne se ferme donc pas tout seul et exige un accuse de
 * reception explicite — fermer par inadvertance couterait une regeneration, et une
 * regeneration casse le formulaire de la boutique jusqu'a ce qu'elle mette a jour son cote.
 *
 * Partage entre la creation et la fiche : les deux montrent la meme chose et doivent
 * l'avertir de la meme facon.
 */
@Component({
  selector: 'app-secret-revele',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatTooltipModule, DatePipe],
  templateUrl: './secret-revele.html',
  styleUrl: './secret-revele.scss',
})
export class SecretRevele {
  private readonly document = inject(DOCUMENT);

  readonly secret = input.required<string>();
  readonly clePublique = input<string | null>(null);
  readonly cheminWebhook = input<string | null>(null);
  /** Facultatif : la creation d'une boutique ne rend aucune fenetre de transition. */
  readonly valideJusquA = input<string | null>(null);

  /** Emis au clic sur l'accuse de reception : c'est l'appelant qui decide de la suite. */
  readonly accuse = output<void>();

  readonly copie = signal<string | null>(null);

  /**
   * L'URL complete, et non le seul chemin : c'est ce que la boutique doit coller dans son
   * formulaire, et l'origine du dashboard est celle de l'API — `apiBaseUrl` est vide dans
   * les deux environnements, le dashboard etant servi derriere le meme domaine.
   */
  urlComplete(): string {
    const chemin = this.cheminWebhook();
    if (!chemin) {
      return '';
    }
    return `${this.document.location.origin}${chemin}`;
  }

  copieDansLePressePapier(texte: string, quoi: string): void {
    navigator.clipboard?.writeText(texte).then(
      () => this.copie.set(quoi),
      () => this.copie.set(null),
    );
  }
}
