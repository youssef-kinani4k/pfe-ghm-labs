import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

/**
 * Barre les ecrans du dashboard tant qu'aucun jeton n'est en main.
 *
 * Le garde rend un `UrlTree` plutot que d'appeler `navigate` : la redirection fait alors
 * partie de la navigation refusee, et l'historique ne garde pas l'etape morte.
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);
  return auth.estConnecte() ? true : router.createUrlTree(['/login']);
};
