import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { StatsApi } from './stats-api';
import { SeriesView } from '../models/monitoring';

describe('StatsApi', () => {
  let api: StatsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(StatsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('envoie la fenetre et la boutique en parametres', () => {
    api.series('11111111-1111-1111-1111-111111111111', 7).subscribe();

    const requete = http.expectOne(
      (r) =>
        r.url === '/api/stats/series' &&
        r.params.get('jours') === '7' &&
        r.params.get('clientId') === '11111111-1111-1111-1111-111111111111',
    );
    expect(requete.request.method).toBe('GET');
    requete.flush({ volume: [], delais: [], intentions: [] } as SeriesView);
  });

  it("n'envoie pas de boutique quand aucune n'est choisie", () => {
    // Un parametre absent ne part pas, sans quoi le serveur ajouterait un predicat pour un
    // filtre que personne n'a demande.
    api.series(undefined, 30).subscribe();

    const requete = http.expectOne((r) => r.url === '/api/stats/series');
    expect(requete.request.params.has('clientId')).toBeFalse();
    expect(requete.request.params.get('jours')).toBe('30');
    requete.flush({ volume: [], delais: [], intentions: [] } as SeriesView);
  });
});
