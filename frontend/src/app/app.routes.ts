import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth-guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'login',
    title: 'Connexion',
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
  },
  {
    path: 'dashboard',
    title: 'Flux des leads',
    canActivate: [authGuard],
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'leads',
    title: 'Leads',
    canActivate: [authGuard],
    loadComponent: () => import('./features/leads/leads').then((m) => m.Leads),
  },
  {
    path: 'leads/:id',
    title: 'Detail du lead',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/leads/lead-detail/lead-detail').then((m) => m.LeadDetail),
  },
  {
    path: 'queue',
    title: "File d'attente",
    canActivate: [authGuard],
    loadComponent: () => import('./features/queue/queue').then((m) => m.Queue),
  },
  {
    path: 'connectors',
    title: 'Connecteurs ERP',
    canActivate: [authGuard],
    loadComponent: () => import('./features/connectors/connectors').then((m) => m.Connectors),
  },
  { path: '**', redirectTo: 'dashboard' },
];
