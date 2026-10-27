import { FourEyesThresholds, SecondApproval } from '../core/api/models';

/** Which threshold an operation is compared with (the server applies the same rule). */
export type FourEyesAction = 'STOCK' | 'REFUND' | 'CANCELLATION';

/** Codes the server answers when the second signature is missing or refused. */
const FOUR_EYES_CODES = new Set([
  'four-eyes-required',
  'four-eyes-same-person',
  'four-eyes-not-authorized',
  'bad-credentials',
  'account-locked',
]);

/**
 * Whether a second person must sign: always for a narcotic, otherwise when the absolute amount
 * is strictly above the threshold of the operation (0 switches the rule off).
 */
export function secondPersonNeeded(
  thresholds: FourEyesThresholds | null,
  action: FourEyesAction,
  amountCents: number,
  narcotic: boolean,
): boolean {
  if (narcotic) {
    return true;
  }
  const threshold = thresholdOf(thresholds, action);
  return threshold > 0 && Math.abs(amountCents) > threshold;
}

/** Threshold of the operation, for the sentence shown next to the second-person fields. */
export function thresholdOf(thresholds: FourEyesThresholds | null, action: FourEyesAction): number {
  if (!thresholds) {
    return 0;
  }
  return action === 'STOCK'
    ? thresholds.adjustmentApprovalCents
    : action === 'REFUND'
      ? thresholds.refundApprovalCents
      : thresholds.cancellationApprovalCents;
}

/** Problem code of a refused request (RFC 9457 body with a {@code code} property). */
export function problemCode(error: unknown): string | null {
  return (error as { error?: { code?: string } })?.error?.code ?? null;
}

/** True when the refusal is about the second signature, so the dialog asks for it. */
export function isFourEyesRefusal(error: unknown): boolean {
  const code = problemCode(error);
  return code !== null && FOUR_EYES_CODES.has(code);
}

/** Request fields of a second signature; absent when nobody signed. */
export function approverFields(
  approval: SecondApproval | null,
): { approverEmail: string; approverPin: string } | Record<string, never> {
  return approval && approval.email.trim() && approval.pin.trim()
    ? { approverEmail: approval.email.trim(), approverPin: approval.pin.trim() }
    : {};
}

/** Both fields filled in. */
export function approvalComplete(approval: SecondApproval | null): boolean {
  return !!approval && approval.email.trim().length > 0 && approval.pin.trim().length >= 4;
}
