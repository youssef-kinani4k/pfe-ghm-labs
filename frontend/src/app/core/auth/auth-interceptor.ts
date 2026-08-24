import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { Auth } from './auth';

/**
 * Pose le Bearer sur tous les appels sauf la connexion — y ajouter un jeton expire ferait
 * echouer la seule requete capable d'en obtenir un neuf.
 *
 * Sur 401, le jeton est vide et l'utilisateur renvoye a /login : c'est le seul traitement
 * possible sans mecanisme de rafraichissement, choix assume avec le stockage du jeton.
 */
export const authInterceptor: HttpInterceptorFn = (requete, suivant) => {
  const auth = inject(Auth);
  const router = inject(Router);
  const jeton = auth.token();

  const envoyee =
    jeton && !requete.url.includes('/api/auth/login')
      ? requete.clone({ setHeaders: { Authorization: `Bearer ${jeton}` } })
      : requete;

  return suivant(envoyee).pipe(
    catchError((erreur: HttpErrorResponse) => {
      if (erreur.status === 401) {
        auth.vide();
        router.navigate(['/login']);
      }
      return throwError(() => erreur);
    }),
  );
};
