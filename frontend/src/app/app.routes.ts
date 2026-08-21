import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Flux des leads',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'leads',
    title: 'Leads',
    loadComponent: () => import('./features/leads/leads').then((m) => m.Leads),
  },
  {
    path: 'queue',
    title: "File d'attente",
    loadComponent: () => import('./features/queue/queue').then((m) => m.Queue),
  },
  {
    path: 'connectors',
    title: 'Connecteurs ERP',
    loadComponent: () => import('./features/connectors/connectors').then((m) => m.Connectors),
  },
  { path: '**', redirectTo: 'dashboard' },
];
