import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Auth } from '../../core/auth/auth';

/**
 * Ecran de connexion du dashboard.
 *
 * Il vit hors de la coquille applicative : afficher la barre de navigation derriere un
 * formulaire de connexion donnerait a voir des ecrans inaccessibles.
 */
@Component({
  selector: 'app-login',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressBarModule,
  ],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  readonly erreur = signal<string | null>(null);
  readonly enCours = signal(false);

  readonly formulaire = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  soumet(): void {
    if (this.formulaire.invalid) {
      this.formulaire.markAllAsTouched();
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    const { username, password } = this.formulaire.getRawValue();
    this.auth.login(username, password).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      // Message unique : le serveur ne distingue pas identifiant inconnu et mot de passe
      // faux, et l'ecran ne doit pas inventer la distinction.
      error: () => {
        this.erreur.set('Identifiants refuses');
        this.enCours.set(false);
      },
    });
  }
}
