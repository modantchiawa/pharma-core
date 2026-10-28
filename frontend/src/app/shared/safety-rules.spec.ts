import { SafetyRule } from '../core/api/models';
import {
  componentPrefixes,
  evaluateBasket,
  normalizeSubstance,
  substanceComponents,
  tokenMatches,
} from './safety-rules';

const RULES: SafetyRule[] = [
  {
    kind: 'INTERACTION',
    substance: 'ACIDE ACETYLSALICYLIQUE',
    counterpart: 'IBUPROFENE',
    severity: 'ADVISED_AGAINST',
    advice: 'Risque ulcéreux majoré.',
  },
  {
    kind: 'INTERACTION',
    substance: 'ATC:M01A',
    counterpart: 'ATC:M01A',
    severity: 'ADVISED_AGAINST',
    advice: 'Deux AINS.',
  },
  {
    kind: 'INTERACTION',
    substance: 'MICONAZOLE',
    counterpart: 'WARFARINE',
    severity: 'CONTRAINDICATED',
    advice: 'Hémorragies graves.',
  },
  {
    kind: 'CONTEXT',
    substance: 'ATC:M01A',
    counterpart: 'PREGNANCY',
    severity: 'CONTRAINDICATED',
    advice: 'Ignoré par le poste : le profil est sur le serveur.',
  },
];

const aspirin = {
  productId: 'a',
  designation: 'ASPEGIC 1000',
  dci: 'Acide acétylsalicylique',
  atcCode: 'N02BA01',
};
const ibuprofen = {
  productId: 'b',
  designation: 'ADVIL 400',
  dci: 'Ibuprofène',
  atcCode: 'M01AE01',
};
const voltarene = {
  productId: 'c',
  designation: 'VOLTARENE',
  dci: 'Diclofénac sodique',
  atcCode: 'M01AB05',
};
const coldrex = {
  productId: 'd',
  designation: 'COLDREX',
  dci: 'Paracétamol + Caféine + Phényléphrine',
};
const doliprane = {
  productId: 'e',
  designation: 'DOLIPRANE 1000',
  dci: 'PARACETAMOL',
  atcCode: 'N02BE01',
};

describe('safety rules (terminal side)', () => {
  it('normalises names like the server', () => {
    expect(normalizeSubstance('Acide acétylsalicylique')).toBe('ACIDE ACETYLSALICYLIQUE');
    expect(substanceComponents('Amoxicilline / acide clavulanique')).toEqual([
      'AMOXICILLINE',
      'ACIDE CLAVULANIQUE',
    ]);
    expect(substanceComponents('Paracétamol + Caféine + Codéine (phosphate)')).toEqual([
      'PARACETAMOL',
      'CAFEINE',
      'CODEINE',
    ]);
    expect(tokenMatches('DICLOFENAC', ['DICLOFENAC SODIQUE'], null)).toBe(true);
    expect(tokenMatches('DICLOFENAC', ['DICLOFENACINE'], null)).toBe(false);
    expect(tokenMatches('ATC:M01A', [], 'M01AE01')).toBe(true);
    expect(componentPrefixes('ACIDE ACETYLSALICYLIQUE')).toEqual([
      'ACIDE',
      'ACIDE ACETYLSALICYLIQUE',
    ]);
  });

  it('finds interactions by name and by ATC class, with the same key as the server', () => {
    const alerts = evaluateBasket([aspirin, ibuprofen, voltarene], RULES);

    expect(alerts.map((a) => a.title)).toEqual([
      'Interaction : ASPEGIC 1000 + ADVIL 400',
      'Même classe : ADVIL 400 + VOLTARENE',
    ]);
    expect(alerts[0].key).toBe('INTERACTION:ACIDE ACETYLSALICYLIQUE|IBUPROFENE:a,b');
    expect(alerts[1].productIds).toEqual(['b', 'c']);
  });

  it('flags a substance hidden in a combination and sorts the most severe first', () => {
    const warfarine = {
      productId: 'w',
      designation: 'COUMADINE',
      dci: 'Warfarine',
      atcCode: 'B01AA03',
    };
    const daktarin = { productId: 'm', designation: 'DAKTARIN', dci: 'Miconazole' };

    const alerts = evaluateBasket([coldrex, doliprane, warfarine, daktarin], RULES);

    expect(alerts.map((a) => [a.kind, a.severity])).toEqual([
      ['INTERACTION', 'CONTRAINDICATED'],
      ['DUPLICATE_SUBSTANCE', 'ADVISED_AGAINST'],
    ]);
    expect(alerts[1].title).toBe('Doublon : PARACETAMOL dans 2 produits');
    expect(alerts[1].key).toBe('DUPLICATE_SUBSTANCE:PARACETAMOL:d,e');
  });

  it('never fires a class rule on a single product nor a context rule', () => {
    expect(evaluateBasket([ibuprofen], RULES)).toEqual([]);
  });
});
