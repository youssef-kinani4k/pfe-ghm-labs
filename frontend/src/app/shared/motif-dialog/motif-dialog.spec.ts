import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MotifData, MotifDialog } from './motif-dialog';

describe('MotifDialog', () => {
  let fermeture: { close: jasmine.Spy };

  function monte(donnees: MotifData) {
    TestBed.resetTestingModule();
    fermeture = { close: jasmine.createSpy('close') };
    TestBed.configureTestingModule({
      imports: [MotifDialog],
      providers: [
        provideZonelessChangeDetection(),
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: fermeture },
        { provide: MAT_DIALOG_DATA, useValue: donnees },
      ],
    });
    const fixture = TestBed.createComponent(MotifDialog);
    fixture.detectChanges();
    return fixture;
  }

  it('refuse de valider sans motif', () => {
    const dialogue = monte({ titre: 'Rejouer ce message' }).componentInstance;
    dialogue.motif.set('  ');
    expect(dialogue.valide()).toBeFalse();

    dialogue.confirme();
    expect(fermeture.close).not.toHaveBeenCalled();
  });

  it('rend le motif nettoye', () => {
    const dialogue = monte({ titre: 'Ecarter ce message' }).componentInstance;
    dialogue.motif.set('  Doublon  ');
    dialogue.confirme();
    expect(fermeture.close).toHaveBeenCalledWith('Doublon');
  });

  it('ferme sans motif quand on annule', () => {
    const dialogue = monte({ titre: 'Rejouer ce message' }).componentInstance;
    dialogue.motif.set('Une raison');
    dialogue.annule();
    // Fermer sans argument et non avec `undefined` : l'appelant distingue les deux par
    // `afterClosed()`, qui rend `undefined` dans les deux cas — c'est l'appel nu qui dit
    // « rien a faire », et le motif saisi ne doit surtout pas fuir.
    expect(fermeture.close).toHaveBeenCalledWith();
  });

  it("affiche l'avertissement du serveur, et rien quand il n'y en a pas", () => {
    const avec = monte({
      titre: 'Rejouer ce message',
      avertissement: 'Ce rejeu reattribuera le lead.',
    });
    expect(avec.nativeElement.textContent).toContain('Ce rejeu reattribuera le lead.');
    expect(avec.nativeElement.querySelector('[role="alert"]')).not.toBeNull();

    const sans = monte({ titre: 'Rejouer ce message' });
    expect(sans.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });
});
