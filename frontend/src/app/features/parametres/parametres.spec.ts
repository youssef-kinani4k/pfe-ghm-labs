import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Parametres } from './parametres';

describe('Parametres', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  function ecran() {
    const fixture = TestBed.createComponent(Parametres);
    fixture.detectChanges();
    return fixture;
  }

  function repondEtat(actif = true, source = 'BASE', apercu: string | null = 'rete') {
    httpMock.expectOne('/api/admin/intent').flush({
      actif,
      cleDefinie: apercu !== null,
      apercu,
      source,
      modele: 'gemini-2.5-flash',
    });
    httpMock
      .expectOne((r) => r.url === '/api/stats')
      .flush({
        total: 0,
        leadsParStatut: {},
        evenementsParStatut: {},
        leadsParIntention: {},
        leadsParSourceDIntention: { GEMINI: 12, RULES: 3 },
        leadsParCommercial: {},
        nomsDeCommercial: {},
      });
  }

  it('affiche l etat de la cle sans jamais la montrer entiere', () => {
    const fixture = ecran();
    repondEtat();

    fixture.detectChanges();
    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(texte).toContain('rete');
    expect(fixture.componentInstance.etat()?.source).toBe('BASE');
  });

  it('eprouve la cle saisie sans l enregistrer', () => {
    const fixture = ecran();
    repondEtat();
    const composant = fixture.componentInstance;

    composant.cleSaisie.set('cle-a-eprouver');
    composant.teste();

    const requete = httpMock.expectOne('/api/admin/intent/test');
    expect(requete.request.body.apiKey).toBe('cle-a-eprouver');
    requete.flush({ ok: true, cause: 'OK', detail: 'Cle valide', intention: 'DEVIS' });
    // Aucun PUT ne doit suivre : eprouver n'est pas mettre en service.
    httpMock.verify();
    expect(composant.diagnostic()?.ok).toBeTrue();
  });

  it('vide le champ apres enregistrement, pour ne pas laisser un secret a l ecran', () => {
    const fixture = ecran();
    repondEtat();
    const composant = fixture.componentInstance;

    composant.cleSaisie.set('AIzaSyExempleDeCleSecrete');
    composant.enregistre();

    httpMock.expectOne('/api/admin/intent').flush({
      actif: true,
      cleDefinie: true,
      apercu: 'rete',
      source: 'BASE',
      modele: 'gemini-2.5-flash',
    });

    expect(composant.cleSaisie()).toBe('');
  });

  it('compte les leads analyses par le modele et par le lexique', () => {
    const fixture = ecran();
    repondEtat();

    expect(fixture.componentInstance.parGemini()).toBe(12);
    expect(fixture.componentInstance.parRegles()).toBe(3);
  });

  afterEach(() => httpMock.verify());
});
