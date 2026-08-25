import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Auth } from './core/auth/auth';

/**
 * Coquille de l'application : barre superieure, menu lateral, zone de contenu.
 *
 * La coquille ne s'affiche que connecte. L'ecran de connexion partage le meme `router-outlet`
 * mais doit apparaitre seul : montrer autour de lui un menu vers des ecrans interdits
 * donnerait a croire qu'ils sont accessibles.
 */
@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);
  readonly auth = inject(Auth);

  readonly menuOuvert = signal(true);

  readonly entrees = [
    { chemin: '/dashboard', libelle: 'Dashboard', icone: 'monitoring' },
    { chemin: '/leads', libelle: 'Leads', icone: 'contacts' },
    { chemin: '/queue', libelle: "File d'attente", icone: 'inbox' },
    { chemin: '/boutiques', libelle: 'Boutiques', icone: 'storefront' },
    { chemin: '/connectors', libelle: 'Connecteurs', icone: 'cable' },
  ];

  basculeMenu(): void {
    this.menuOuvert.update((ouvert) => !ouvert);
  }

  deconnecte(): void {
    this.auth.vide();
    this.router.navigate(['/login']);
  }
}
