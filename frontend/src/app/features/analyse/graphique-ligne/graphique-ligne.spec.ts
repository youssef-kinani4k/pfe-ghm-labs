import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GraphiqueLigne, SerieGraphique } from './graphique-ligne';

describe('GraphiqueLigne', () => {
  let fixture: ComponentFixture<GraphiqueLigne>;

  const serie: SerieGraphique[] = [
    { nom: 'Mediane', valeurs: [1, null, 3], couleur: '#334155', remplie: false },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [GraphiqueLigne] }).compileComponents();
    fixture = TestBed.createComponent(GraphiqueLigne);
  });

  it('rend un canvas', () => {
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('canvas')).toBeTruthy();
  });

  it('detruit son instance Chart a la destruction du composant', () => {
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.detectChanges();

    // Sans cela, chaque navigation vers l'ecran laisse une instance vivante, avec son
    // ecouteur de redimensionnement.
    const instance = fixture.componentInstance;
    const graphique = (instance as unknown as { graphique?: { destroy: () => void } }).graphique;
    expect(graphique).toBeTruthy();
    const espion = spyOn(graphique!, 'destroy').and.callThrough();

    fixture.destroy();

    expect(espion).toHaveBeenCalled();
  });

  it('empile l axe a 100 % quand empile est demande', () => {
    // C'est la seule forme empilee de l'ecran d'analyse (l'aire des intentions Gemini /
    // lexique) : sans stacked et sans borne a 100, l'axe recommencerait a zero pour chaque
    // serie, exactement le defaut corrige par F13.
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.componentRef.setInput('empile', true);
    fixture.detectChanges();

    const instance = fixture.componentInstance;
    const graphique = (
      instance as unknown as {
        graphique?: { options: { scales?: { y?: Record<string, unknown> } } };
      }
    ).graphique;

    expect(graphique?.options.scales?.y?.['stacked']).toBe(true);
    expect(graphique?.options.scales?.y?.['max']).toBe(100);
  });

  it('met a jour le graphique existant quand les entrees changent apres coup', () => {
    fixture.componentRef.setInput('libelles', ['lun', 'mar', 'mer']);
    fixture.componentRef.setInput('series', serie);
    fixture.detectChanges();

    // Meme transtypage que le test de destruction : c'est la seule facon d'observer
    // l'instance Chart.js privee depuis le test.
    const instance = fixture.componentInstance;
    const graphique = (
      instance as unknown as {
        graphique?: { data: { labels?: unknown[]; datasets: { data: unknown[] }[] } };
      }
    ).graphique;
    expect(graphique).toBeTruthy();

    const nouvelleSerie: SerieGraphique[] = [
      { nom: 'Mediane', valeurs: [9, 8, 7], couleur: '#334155', remplie: false },
    ];
    fixture.componentRef.setInput('libelles', ['jeu', 'ven', 'sam']);
    fixture.componentRef.setInput('series', nouvelleSerie);
    fixture.detectChanges();

    // Ce chemin passe par l'effect() du constructeur, pas par ngAfterViewInit : c'est
    // exactement ce que l'ecran d'analyse declenche a chaque changement de periode ou de
    // boutique.
    expect(graphique!.data.labels).toEqual(['jeu', 'ven', 'sam']);
    expect(graphique!.data.datasets[0].data).toEqual([9, 8, 7]);
  });
});
