import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ReattributionDialog } from './reattribution-dialog';

describe('ReattributionDialog', () => {
  let http: HttpTestingController;
  let fermeture: { close: jasmine.Spy };

  function monte(statut: string) {
    TestBed.resetTestingModule();
    fermeture = { close: jasmine.createSpy('close') };
    TestBed.configureTestingModule({
      imports: [ReattributionDialog],
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: fermeture },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            leadId: 'lead-1',
            clientId: 'client-1',
            salesRepId: 'rep-1',
            statut,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(ReattributionDialog);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/clients/client-1/sales-reps').flush([
      { id: 'rep-1', fullName: 'Amina', email: 'a@x.fr', active: true },
      { id: 'rep-2', fullName: 'Karim', email: 'k@x.fr', active: true },
      { id: 'rep-3', fullName: 'Sofia', email: 's@x.fr', active: false },
    ]);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http.verify());

  it('n offre ni le commercial en place ni les desactives', () => {
    const dialogue = monte('ROUTED').componentInstance;
    expect(dialogue.candidats().map((c: { id: string }) => c.id)).toEqual(['rep-2']);
  });

  it('avertit seulement quand le lead est deja synchronise', () => {
    expect(monte('ROUTED').componentInstance.avertitSurERP()).toBeFalse();
    expect(monte('SYNCED').componentInstance.avertitSurERP()).toBeTrue();
  });

  it('refuse de valider sans motif', () => {
    const dialogue = monte('ROUTED').componentInstance;
    dialogue.commercialChoisi.set('rep-2');
    dialogue.motif.set('   ');
    expect(dialogue.valide()).toBeFalse();

    dialogue.confirme();
    http.expectNone('/api/leads/lead-1/reassign');
  });

  it('ferme en rendant la fiche rechargee', () => {
    const dialogue = monte('ROUTED').componentInstance;
    dialogue.commercialChoisi.set('rep-2');
    dialogue.motif.set('Depart en conge');
    dialogue.confirme();

    const requete = http.expectOne('/api/leads/lead-1/reassign');
    expect(requete.request.body).toEqual({
      salesRepId: 'rep-2',
      reason: 'Depart en conge',
    });
    requete.flush({ id: 'lead-1' });
    expect(fermeture.close).toHaveBeenCalledWith({ id: 'lead-1' });
  });

  it('affiche la phrase du refus serveur, jamais le corps brut', () => {
    const dialogue = monte('ROUTED').componentInstance;
    dialogue.commercialChoisi.set('rep-2');
    dialogue.motif.set('Erreur de saisie');
    dialogue.confirme();

    http
      .expectOne('/api/leads/lead-1/reassign')
      .flush(
        { title: 'Conflict', detail: 'Ce lead est deja attribue a ce commercial.' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(dialogue.erreur()).toBe('Ce lead est deja attribue a ce commercial.');
    expect(dialogue.enCours()).toBeFalse();
    expect(fermeture.close).not.toHaveBeenCalled();
  });
});
