import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { tap } from 'rxjs';

const CLE_JETON = 'leadflow.token';
const CLE_EXPIRATION = 'leadflow.expiresAt';
const CLE_UTILISATEUR = 'leadflow.username';

interface LoginResponse {
  token: string;
  expiresAt: string;
}

/**
 * Etat d'authentification du dashboard.
 *
 * Le jeton vit dans localStorage : il doit survivre au rechargement, ce qu'une session de
 * huit heures rend necessaire. Le choix l'expose a une XSS ; la contrepartie est qu'il n'y
 * a ni cookie ni CSRF a gerer sur une API STATELESS. Pas de rafraichissement : a
 * l'expiration, retour a l'ecran de connexion.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);

  readonly token = signal<string | null>(localStorage.getItem(CLE_JETON));
  readonly utilisateur = signal<string | null>(localStorage.getItem(CLE_UTILISATEUR));
  readonly estConnecte = computed(() => this.token() !== null);

  login(username: string, password: string) {
    return this.http
      .post<LoginResponse>('/api/auth/login', { username, password })
      .pipe(tap((reponse) => this.applique(reponse.token, reponse.expiresAt, username)));
  }

  applique(token: string, expiresAt: string, username?: string): void {
    localStorage.setItem(CLE_JETON, token);
    localStorage.setItem(CLE_EXPIRATION, expiresAt);
    this.token.set(token);
    if (username) {
      localStorage.setItem(CLE_UTILISATEUR, username);
      this.utilisateur.set(username);
    }
  }

  vide(): void {
    localStorage.removeItem(CLE_JETON);
    localStorage.removeItem(CLE_EXPIRATION);
    localStorage.removeItem(CLE_UTILISATEUR);
    this.token.set(null);
    this.utilisateur.set(null);
  }
}
