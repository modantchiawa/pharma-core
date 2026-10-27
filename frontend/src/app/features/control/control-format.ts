import {
  ActivityDay,
  ControlAlert,
  ControlAlertKind,
  ControlMetric,
  EmployeeControl,
  WriteOffKind,
} from '../../core/api/models';
import { IconName } from '../../shared/ui/icons';

/** How a metric reads on the employee card, in the order shown. */
export interface MetricView {
  metric: ControlMetric;
  label: string;
  unit: 'count' | 'money' | 'percent';
  icon: IconName;
}

export const METRICS: MetricView[] = [
  {
    metric: 'CANCELLATIONS_AFTER_PAYMENT',
    label: 'Annulations après paiement',
    unit: 'count',
    icon: 'ban',
  },
  { metric: 'CANCELLATION_RATE', label: 'Taux d’annulation', unit: 'percent', icon: 'trendUp' },
  { metric: 'DISCOUNTS', label: 'Remises accordées', unit: 'money', icon: 'coins' },
  {
    metric: 'SUPERVISOR_OVERRIDES',
    label: 'Validations demandées',
    unit: 'count',
    icon: 'key',
  },
  { metric: 'STOCK_LOSSES', label: 'Sorties et ajustements', unit: 'money', icon: 'stock' },
  { metric: 'RETURNS', label: 'Retours remboursés', unit: 'count', icon: 'receipt' },
  { metric: 'CASH_VARIANCE', label: 'Écarts de caisse', unit: 'money', icon: 'cash' },
  {
    metric: 'COUNT_DISCREPANCIES',
    label: 'Écarts au contrôle du jour',
    unit: 'count',
    icon: 'counts',
  },
  { metric: 'OUTSIDE_HOURS', label: 'Hors heures d’activité', unit: 'count', icon: 'clock' },
  { metric: 'SHORTFALL_SALES', label: 'Ventes sans stock', unit: 'count', icon: 'alert' },
];

/** Secondary figure under a metric (amount behind a count, or the count behind an amount). */
export function metricFoot(employee: EmployeeControl, metric: ControlMetric): string | null {
  switch (metric) {
    case 'CANCELLATIONS_AFTER_PAYMENT':
      return `${employee.cancellations} annulation(s) au total`;
    case 'CANCELLATION_RATE':
      return `${employee.sales} vente(s)`;
    case 'STOCK_LOSSES':
      return `${employee.adjustments} ajustement(s) · ${employee.writeOffs} sortie(s)`;
    case 'CASH_VARIANCE':
      return `${employee.cashSessions} session(s) clôturée(s)`;
    default:
      return null;
  }
}

export function flaggedCount(employee: EmployeeControl): number {
  return employee.flags.filter((flag) => flag.flagged).length;
}

export const ALERT_LABELS: Record<ControlAlertKind, string> = {
  LARGE_ADJUSTMENT: 'Sortie de stock',
  CANCELLATION_AFTER_PAYMENT: 'Annulation',
  CASH_VARIANCE: 'Caisse',
  COUNT_DISCREPANCY: 'Inventaire',
  OUTSIDE_HOURS: 'Hors horaires',
  PIN_FAILURES: 'Codes PIN',
  RECEIPT_DISCREPANCY: 'Réception',
  TEST: 'Test',
};

export const ALERT_ICONS: Record<ControlAlertKind, IconName> = {
  LARGE_ADJUSTMENT: 'stock',
  CANCELLATION_AFTER_PAYMENT: 'ban',
  CASH_VARIANCE: 'cash',
  COUNT_DISCREPANCY: 'safe',
  OUTSIDE_HOURS: 'clock',
  PIN_FAILURES: 'lock',
  RECEIPT_DISCREPANCY: 'receive',
  TEST: 'bell',
};

export function severityBadge(alert: Pick<ControlAlert, 'severity'>): string {
  return alert.severity === 'CRITICAL'
    ? 'badge-danger'
    : alert.severity === 'WARNING'
      ? 'badge-warning'
      : 'badge-info';
}

export const WRITE_OFF_KINDS: { kind: WriteOffKind; label: string }[] = [
  { kind: 'CASSE', label: 'Casse' },
  { kind: 'PERIME', label: 'Périmé' },
  { kind: 'PERTE', label: 'Perte' },
  { kind: 'VOL', label: 'Vol' },
];

/** Labels of the narcotics register lines. */
export const MOVEMENT_LABELS: Record<string, string> = {
  RECEIPT: 'Entrée (réception)',
  SALE: 'Délivrance',
  SALE_CANCELLATION: 'Annulation de vente',
  SALE_RETURN: 'Retour client',
  ADJUSTMENT: 'Correction manuelle',
  WRITE_OFF: 'Sortie déclarée',
  EXPIRY_WRITE_OFF: 'Sortie périmé (auto)',
  TRANSFER_OUT: 'Cession à une officine',
  TRANSFER_IN: 'Réception d’une officine',
  COUNT: 'Inventaire',
};

/** Refund of a partial return: the line's net amount pro rata, rounded like the server. */
export function returnRefund(lineNetCents: number, lineQuantity: number, returned: number): number {
  if (lineQuantity <= 0 || returned <= 0) {
    return 0;
  }
  return Math.round((lineNetCents * returned) / lineQuantity);
}

const DAYS: ActivityDay['day'][] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

export const DAY_LABELS: Record<ActivityDay['day'], string> = {
  MONDAY: 'Lundi',
  TUESDAY: 'Mardi',
  WEDNESDAY: 'Mercredi',
  THURSDAY: 'Jeudi',
  FRIDAY: 'Vendredi',
  SATURDAY: 'Samedi',
  SUNDAY: 'Dimanche',
};

/** One editable weekday row of the settings screen; a closed day keeps its last times. */
export interface HoursRow {
  day: ActivityDay['day'];
  open: boolean;
  from: string;
  to: string;
}

export function hoursRows(days: ActivityDay[]): HoursRow[] {
  return DAYS.map((day) => {
    const slot = days.find((d) => d.day === day);
    return slot
      ? { day, open: true, from: slot.open.slice(0, 5), to: slot.close.slice(0, 5) }
      : { day, open: false, from: '08:00', to: '20:00' };
  });
}

/** Back to the wire format; returns the problem when a row is not valid. */
export function activityDays(rows: HoursRow[]): { days: ActivityDay[]; problem: string | null } {
  const open = rows.filter((row) => row.open);
  if (open.length === 0) {
    return { days: [], problem: 'Indiquez au moins un jour d’ouverture' };
  }
  const wrong = open.find((row) => !row.from || !row.to || row.to <= row.from);
  if (wrong) {
    return {
      days: [],
      problem: `${DAY_LABELS[wrong.day]} : la fermeture doit suivre l’ouverture`,
    };
  }
  return {
    days: open.map((row) => ({ day: row.day, open: row.from, close: row.to })),
    problem: null,
  };
}

const SHORT_WHEN = new Intl.DateTimeFormat('fr-FR', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
});

/** "27 oct., 23:20": when an alert happened, in the browser's time zone. */
export function shortWhen(iso: string): string {
  return SHORT_WHEN.format(new Date(iso));
}

/** ISO date (YYYY-MM-DD) of today minus {@code days}, in the browser's time zone. */
export function isoDaysAgo(days: number, now = new Date()): string {
  const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}
