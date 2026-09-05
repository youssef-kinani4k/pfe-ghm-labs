import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Analyse } from './analyse';
import { SeriesView } from '../../core/models/monitoring';

describe('Analyse', () => {
  let fixture: ComponentFixture<Analyse>;
  let http: HttpTestingController;

  const vide: SeriesView = { volume: [], delais: [], intentions: [] };

  const peuplee: SeriesView = {
    volume: [{ jour: '2026-03-02', captures: 5, ecartes: 1 }],
    delais: [{ jour: '2026-03-02', medianeSecondes: 4.2, p95Secondes: 30 }],
    intentions: [{ jour: '2026-03-02', gemini: 4, lexique: 1 }],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Analyse],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(Analyse);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function repondAuxAppels(vue: SeriesView) {
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/clients' || r.url === '/api/clients').flush([]);
    http.expectOne((r) => r.url === '/api/stats/series').flush(vue);
    fixture.detectChanges();
  }

  it('demande trente jours par defaut', () => {
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/admin/clients' || r.url === '/api/clients').flush([]);
    const requete = http.expectOne((r) => r.url === '/api/stats/series');
    expect(requete.request.params.get('jours')).toBe('30');
    requete.flush(vide);
  });

  it("affiche un etat vide plutot qu'un graphique plat", () => {
    // Un canvas plat se lit comme une panne. L'absence de donnee doit se dire.
    repondAuxAppels(vide);

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Aucune donnee');
    expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
  });

  it('dessine les trois figures quand il y a des donnees', () => {
    repondAuxAppels(peuplee);

    expect(fixture.nativeElement.querySelectorAll('canvas').length).toBe(3);
  });
});
