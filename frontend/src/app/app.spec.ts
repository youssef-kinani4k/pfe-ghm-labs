import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import { Auth } from './core/auth/auth';

describe('App', () => {
  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('cache la coquille tant que personne n est connecte', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    // Sinon l'ecran de connexion apparaitrait entoure d'un menu vers des ecrans interdits.
    expect(compiled.querySelector('.app-barre__marque')).toBeNull();
  });

  it('affiche la barre et le menu une fois connecte', () => {
    TestBed.inject(Auth).applique('jeton-de-test', '2026-08-24T00:00:00Z', 'operateur');

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.app-barre__marque')?.textContent).toContain('LeadFlow');
    // Sept entrees : Analyse rejoint Dashboard, Leads, File d'attente, Boutiques, Connecteurs
    // et Parametres. Le compte est asserte pour qu'un ajout de route se decide, pas se subisse.
    expect(compiled.querySelectorAll('mat-nav-list a').length).toBe(7);
  });
});
