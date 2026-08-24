import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { Auth } from './auth';
import { authInterceptor } from './auth-interceptor';

describe('Auth', () => {
  let auth: Auth;
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    localStorage.clear();
    router = jasmine.createSpyObj('Router', ['navigate']);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
      ],
    });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('stocke le jeton et expose un signal de connexion', () => {
    auth.login('operateur', 'secret').subscribe();

    httpMock.expectOne('/api/auth/login').flush({
      token: 'jeton-de-test',
      expiresAt: '2026-08-24T00:00:00Z',
    });

    expect(auth.token()).toBe('jeton-de-test');
    expect(auth.estConnecte()).toBeTrue();
    // Survit au rechargement : une session de huit heures l'exige.
    expect(localStorage.getItem('leadflow.token')).toBe('jeton-de-test');
  });

  it("pose l'en-tete Authorization sur les appels API", () => {
    auth.applique('jeton-de-test', '2026-08-24T00:00:00Z');

    http.get('/api/leads').subscribe();

    const requete = httpMock.expectOne('/api/leads');
    expect(requete.request.headers.get('Authorization')).toBe('Bearer jeton-de-test');
    requete.flush({});
  });

  it("ne pose pas d'en-tete sur la connexion elle-meme", () => {
    auth.applique('jeton-de-test', '2026-08-24T00:00:00Z');

    http.post('/api/auth/login', {}).subscribe();

    const requete = httpMock.expectOne('/api/auth/login');
    expect(requete.request.headers.has('Authorization')).toBeFalse();
    requete.flush({});
  });

  it('vide le jeton et renvoie a la connexion sur 401', () => {
    auth.applique('jeton-expire', '2026-08-24T00:00:00Z');

    http.get('/api/leads').subscribe({ error: () => {} });
    httpMock.expectOne('/api/leads').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.token()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
