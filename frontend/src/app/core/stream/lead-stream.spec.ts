import { decoupeTrames } from './lead-stream';

describe('decoupeTrames', () => {
  it('extrait un evenement complet et rend le reste', () => {
    const { evenements, reste } = decoupeTrames('event: lead\ndata: {"leadId":"a"}\n\n');

    expect(evenements).toEqual([{ nom: 'lead', donnees: '{"leadId":"a"}' }]);
    expect(reste).toBe('');
  });

  it('conserve une trame incomplete pour le morceau suivant', () => {
    // Un ReadableStream livre des morceaux arbitraires : une trame coupee en deux ne doit
    // ni etre perdue, ni etre lue a moitie.
    const premier = decoupeTrames('event: lead\ndata: {"leadI');
    expect(premier.evenements).toEqual([]);

    const second = decoupeTrames(premier.reste + 'd":"a"}\n\n');
    expect(second.evenements).toEqual([{ nom: 'lead', donnees: '{"leadId":"a"}' }]);
  });

  it('extrait deux evenements arrives ensemble', () => {
    const { evenements } = decoupeTrames(
      'event: lead\ndata: {"leadId":"a"}\n\nevent: dead-letter\ndata: {"id":"b"}\n\n',
    );

    expect(evenements.map((e) => e.nom)).toEqual(['lead', 'dead-letter']);
  });

  it('ignore les commentaires de maintien', () => {
    const { evenements } = decoupeTrames(': keep-alive\n\n');

    // Le commentaire existe pour empecher un proxy de couper la connexion, pas pour
    // produire une ligne a l'ecran.
    expect(evenements).toEqual([]);
  });

  it('recolle une charge utile ecrite sur plusieurs lignes data', () => {
    const { evenements } = decoupeTrames('event: lead\ndata: {"a":1,\ndata: "b":2}\n\n');

    // La specification SSE autorise le fractionnement d'une charge sur plusieurs lignes
    // `data:` ; les recoller avec un saut de ligne est ce que fait tout client conforme.
    expect(evenements).toEqual([{ nom: 'lead', donnees: '{"a":1,\n"b":2}' }]);
  });
});
