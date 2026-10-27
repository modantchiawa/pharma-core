import { LicenseStateView, SubscriptionPaymentMethod } from '../api/models';
import { IconName } from '../../shared/ui/icons';
import { instantOf } from './license';

/* Wording of the subscription state, loaded with the pages and the banner that show it. */

export interface LicenseNotice {
  tone: 'warning' | 'danger';
  icon: IconName;
  title: string;
  message: string;
}

export function daysLabel(days: number | null): string {
  if (days === null) {
    return '';
  }
  if (days <= 0) {
    return "moins d'un jour";
  }
  return days === 1 ? '1 jour' : `${days} jours`;
}

/** « 1er novembre 2026 », « 27 octobre 2026 ». */
export function frenchDate(iso: string | null): string {
  const at = instantOf(iso);
  if (at === null) {
    return '—';
  }
  const text = new Intl.DateTimeFormat('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(at);
  return text.replace(/^1 /, '1er ');
}

/** « il y a 3 min », « il y a 2 h », « il y a 4 jours ». */
export function ago(iso: string | null, now: number): string {
  const at = instantOf(iso);
  if (at === null) {
    return 'jamais';
  }
  const minutes = Math.max(0, Math.floor((now - at) / 60_000));
  if (minutes < 1) {
    return "à l'instant";
  }
  if (minutes < 60) {
    return `il y a ${minutes} min`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `il y a ${hours} h`;
  }
  const days = Math.round(hours / 24);
  return days === 1 ? 'il y a 1 jour' : `il y a ${days} jours`;
}

/** Shell banner, worded per reason; {@code null} when everything is in order. */
export function bannerFor(state: LicenseStateView | null): LicenseNotice | null {
  if (!state || state.status === 'ACTIVE') {
    return null;
  }
  const date = frenchDate(state.deadline);
  const left = daysLabel(state.daysLeft);
  switch (state.reason) {
    case 'PAYMENT_DUE':
      return {
        tone: 'warning',
        icon: 'calendar',
        title: `Abonnement : plus que ${left}`,
        message: `Il se termine le ${date}. Renouvelez-le auprès de l'éditeur pour éviter toute interruption des ventes.`,
      };
    case 'OFFLINE_LIMIT':
      return {
        tone: 'warning',
        icon: 'cloudOff',
        title: `Licence à renouveler par Internet sous ${left}`,
        message: `Le serveur de l'officine n'a pas joint le cloud depuis longtemps : connectez-le avant le ${date}, sinon les ventes seront bloquées.`,
      };
    case 'EXPIRED':
      return state.status === 'GRACE'
        ? {
            tone: 'danger',
            icon: 'alert',
            title: `Abonnement expiré — régularisez avant le ${date}`,
            message: `Les ventes restent possibles encore ${left}. Au-delà, ventes et modifications seront bloquées.`,
          }
        : {
            tone: 'danger',
            icon: 'ban',
            title: 'Abonnement expiré : ventes et modifications bloquées',
            message:
              "Renouvelez l'abonnement auprès de l'éditeur. La consultation, l'ordonnancier, les rapports et les exports restent accessibles.",
          };
    case 'SUSPENDED':
      return {
        tone: 'danger',
        icon: 'ban',
        title: "Abonnement suspendu par l'éditeur",
        message:
          "Ventes et modifications bloquées. La consultation, l'ordonnancier, les rapports et les exports restent accessibles.",
      };
    case 'OFFLINE_TOO_LONG':
      return {
        tone: 'danger',
        icon: 'cloudOff',
        title: 'Licence expirée : plus de 15 jours sans connexion au cloud',
        message:
          "Connectez le serveur de l'officine à Internet pour renouveler la licence. Ventes et modifications bloquées en attendant.",
      };
    case 'CLOCK_TAMPERED':
      return {
        tone: 'danger',
        icon: 'clock',
        title: 'Horloge du poste incohérente',
        message:
          "Corrigez la date et l'heure du poste et du serveur, puis connectez-les à Internet. Ventes et modifications bloquées.",
      };
    case 'NO_LICENSE':
      return state.status === 'BLOCKED'
        ? {
            tone: 'danger',
            icon: 'license',
            title: 'Aucune licence valide',
            message:
              "Connectez le serveur de l'officine à Internet pour recevoir la licence. Ventes et modifications bloquées.",
          }
        : {
            tone: 'warning',
            icon: 'license',
            title: `Licence à recevoir sous ${left}`,
            message: `Connectez le serveur de l'officine à Internet avant le ${date} pour recevoir sa licence.`,
          };
    default:
      return null;
  }
}

export const PAYMENT_METHOD_LABELS: Record<SubscriptionPaymentMethod, string> = {
  MOBILE_MONEY: 'Mobile money',
  VIREMENT: 'Virement',
  ESPECES: 'Espèces',
  CHEQUE: 'Chèque',
  AUTRE: 'Autre',
};

/** Short status for badges, in the vendor console and on the pharmacy's page. */
export function statusLabel(state: LicenseStateView | null): string {
  if (!state) {
    return 'Inconnu';
  }
  switch (state.status) {
    case 'ACTIVE':
      return 'Actif';
    case 'WARNING':
      return state.reason === 'PAYMENT_DUE' ? 'À renouveler' : 'Licence à renouveler';
    case 'GRACE':
      return 'En retard · grâce';
    case 'BLOCKED':
      return state.reason === 'SUSPENDED'
        ? 'Suspendu'
        : state.reason === 'EXPIRED'
          ? 'Expiré · bloqué'
          : 'Bloqué';
  }
}

export function statusTone(state: LicenseStateView | null): 'success' | 'warning' | 'danger' {
  if (!state || state.status === 'ACTIVE') {
    return 'success';
  }
  return state.status === 'WARNING' ? 'warning' : 'danger';
}

/** Why the counter refuses to validate a sale, {@code null} when it may. */
export function saleRefusal(state: LicenseStateView | null): string | null {
  if (state?.status !== 'BLOCKED') {
    return null;
  }
  return `Vente impossible — ${bannerFor(state)?.title ?? 'abonnement bloqué'}.`;
}
