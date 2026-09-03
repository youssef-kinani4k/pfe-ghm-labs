import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

export interface MotifData {
  titre: string;
  /**
   * Phrase deja redigee — celle que le serveur calcule dans `replayWarning`, ou celle de
   * l'ecart. Affichee telle quelle : ce composant ne compose aucune phrase lui-meme.
   */
  avertissement?: string;
  /** Mot de l'action, sur le bouton de confirmation. */
  action?: string;
}

/**
 * Saisie d'un motif obligatoire, partagee par le rejeu et l'ecart d'un message mort.
 *
 * Dans `shared/` et non dans l'ecran du journal : le geste est le meme des deux cotes, et
 * un motif obligatoire ne se demande pas avec un `confirm()`, qui ne sait pas lire du texte.
 * C'est aussi ce qui remplace les trois `confirm()` natifs de cet ecran — le navigateur ne
 * les met pas au ton du dashboard et n'en rend que « oui » ou « non ».
 */
@Component({
  selector: 'app-motif-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './motif-dialog.html',
  styleUrl: './motif-dialog.scss',
})
export class MotifDialog {
  private readonly fermeture = inject<MatDialogRef<MotifDialog, string>>(MatDialogRef);
  readonly data = inject<MotifData>(MAT_DIALOG_DATA);

  readonly motif = signal('');
  readonly valide = computed(() => this.motif().trim().length > 0);

  confirme(): void {
    if (!this.valide()) {
      return;
    }
    // Nettoye ici et non cote appelant : le motif part vers un journal qu'on relira dans
    // six mois, et une ligne faite d'espaces y serait indistinguable d'un motif absent.
    this.fermeture.close(this.motif().trim());
  }

  annule(): void {
    this.fermeture.close();
  }
}
