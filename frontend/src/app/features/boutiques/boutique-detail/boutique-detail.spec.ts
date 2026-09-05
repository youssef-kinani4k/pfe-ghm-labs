import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BoutiqueDetail } from './boutique-detail';
import { ClientDetailAdmin } from '../../../core/models/tenant';

describe('BoutiqueDetail', () => {
  let httpMock: HttpTestingController;

  const FICHE: ClientDetailAdmin = {
    id: 'boutique-1',
    name: 'Boutique Test',
    publicKey: 'cle-publique',
    webhookPath: '/api/webhooks/leads/cle-publique',
    crmProviderId: 'dolibarr',
    crmSettings: {},
    assignmentStrategy: 'ROUND_ROBIN',
    active: true,
    salesReps: [],
    transition: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideNoopAnimations(),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'boutique-1' }) } },
        },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function ecran(fiche: ClientDetailAdmin = FICHE) {
    const fixture = TestBed.createComponent(BoutiqueDetail);
    fixture.detectChanges();
    httpMock.expectOne('/api/admin/crm/providers').flush([]);
    httpMock.expectOne('/api/admin/clients/boutique-1').flush(fiche);
    fixture.detectChanges();
    return fixture;
  }

  /**
   * Le bandeau et le panneau secret-revele sont visibles ensemble juste apres une rotation :
   * c'est l'etat normal de l'ecran, pas un cas tordu. Si l'operateur revoque depuis le
   * bandeau sans avoir encore ferme le panneau, celui-ci ne doit plus affirmer une fenetre
   * de transition qui vient de se fermer — une affirmation contredite est le pire message
   * possible sur un ecran qui parle de secrets.
   */
  it('efface la mention de la fenetre du panneau secret-revele apres revocation', () => {
    const fixture = ecran();
    const composant = fixture.componentInstance;
    spyOn(window, 'confirm').and.returnValue(true);

    composant.regenereLeSecret();
    httpMock
      .expectOne('/api/admin/clients/boutique-1/rotate-secret')
      .flush({ hmacSecret: 'nouveau-secret', ancienSecretValideJusquA: '2026-09-06T14:30:00Z' });
    httpMock.expectOne('/api/admin/clients/boutique-1').flush({
      ...FICHE,
      transition: { expireLe: '2026-09-06T14:30:00Z', dernierLeadAncienSecret: null },
    });
    fixture.detectChanges();

    let texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('ancien secret reste accepte jusqu au');

    composant.revoqueLeSecretPrecedent();
    httpMock
      .expectOne('/api/admin/clients/boutique-1/revoke-previous-secret')
      .flush({ ...FICHE, transition: null });
    fixture.detectChanges();

    texte = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).not.toContain('ancien secret reste accepte jusqu au');
  });
});
