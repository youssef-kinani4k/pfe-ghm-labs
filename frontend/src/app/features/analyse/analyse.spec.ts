import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { Analyse } from './analyse';
import { SeriesView } from '../../core/models/monitoring';

describe('Analyse', () => {
  let fixture: ComponentFixture<Analyse>;
  let http: HttpTestingController;

  // Le contrat garantit toujours "jours" points, combles a zero par le serveur : le vide
  // reel se presente comme 30 points a zero, jamais comme des listes vides. Un test qui
  // repond avec des listes vides passe par accident et ne verrouille rien.
  const JOURS = joursDepuis('2026-02-01', 30);

  const vide: SeriesView = {
    volume: JOURS.map((jour) => ({ jour, captures: 0, ecartes: 0 })),
    delais: JOURS.map((jour) => ({ jour, medianeSecondes: null, p95Secondes: null })),
    intentions: JOURS.map((jour) => ({ jour, gemini: 0, lexique: 0 })),
  };

  const peuplee: SeriesView = {
    volume: JOURS.map((jour) => ({ jour, captures: 5, ecartes: 1 })),
    delais: JOURS.map((jour) => ({ jour, medianeSecondes: 4.2, p95Secondes: 30 })),
    intentions: JOURS.map((jour) => ({ jour, gemini: 4, lexique: 1 })),
  };

  // Le cas de la correction F13 : un lead capture bien avant la fenetre et synchronise
  // aujourd'hui (un rejeu apres une panne ERP) donne un volume a zero sur toute la fenetre
  // et pourtant un point de delai reel. L'ecran ne doit pas se dire vide pour autant.
  const volumeAZeroMaisUnDelaiReel: SeriesView = {
    volume: JOURS.map((jour) => ({ jour, captures: 0, ecartes: 0 })),
    delais: JOURS.map((jour, i) =>
      i === 0
        ? { jour, medianeSecondes: 120, p95Secondes: 300 }
        : { jour, medianeSecondes: null, p95Secondes: null },
    ),
    intentions: JOURS.map((jour) => ({ jour, gemini: 0, lexique: 0 })),
  };

  function joursDepuis(depuis: string, n: number): string[] {
    const base = new Date(`${depuis}T00:00:00Z`);
    return Array.from({ length: n }, (_, i) => {
      const jour = new Date(base);
      jour.setUTCDate(base.getUTCDate() + i);
      return jour.toISOString().slice(0, 10);
    });
  }

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

  it("n'affiche pas l'etat vide quand seul le delai porte une donnee", () => {
    // Un lead capture il y a quarante jours et synchronise aujourd'hui (un rejeu apres une
    // panne ERP) donne un volume a zero sur toute la fenetre et pourtant un point de delai
    // reel. Juger l'ecran vide sur le seul volume afficherait "Aucune donnee", ce qui est
    // faux et se lit comme la panne que cet etat vide devait justement eviter.
    repondAuxAppels(volumeAZeroMaisUnDelaiReel);

    const texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).not.toContain('Aucune donnee');
    expect(fixture.nativeElement.querySelectorAll('canvas').length).toBe(3);
  });
});
