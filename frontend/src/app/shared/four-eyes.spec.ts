import {
  approvalComplete,
  approverFields,
  isFourEyesRefusal,
  problemCode,
  secondPersonNeeded,
  thresholdOf,
} from './four-eyes';

const thresholds = {
  adjustmentApprovalCents: 10_000,
  refundApprovalCents: 5_000,
  cancellationApprovalCents: 5_000,
};

describe('four eyes', () => {
  it('asks for a second person strictly above the threshold of the operation', () => {
    expect(secondPersonNeeded(thresholds, 'STOCK', 10_000, false)).toBe(false);
    expect(secondPersonNeeded(thresholds, 'STOCK', 10_001, false)).toBe(true);
    expect(secondPersonNeeded(thresholds, 'STOCK', -12_000, false)).toBe(true);
    expect(secondPersonNeeded(thresholds, 'REFUND', 5_001, false)).toBe(true);
    expect(secondPersonNeeded(thresholds, 'CANCELLATION', 4_000, false)).toBe(false);
  });

  it('always for a narcotic, and never when the rule is off', () => {
    expect(secondPersonNeeded(thresholds, 'CANCELLATION', 1, true)).toBe(true);
    expect(secondPersonNeeded(null, 'STOCK', 1, true)).toBe(true);
    const off = {
      adjustmentApprovalCents: 0,
      refundApprovalCents: 0,
      cancellationApprovalCents: 0,
    };
    expect(secondPersonNeeded(off, 'STOCK', 1_000_000, false)).toBe(false);
    expect(secondPersonNeeded(null, 'STOCK', 1_000_000, false)).toBe(false);
  });

  it('reads the threshold of the operation', () => {
    expect(thresholdOf(thresholds, 'STOCK')).toBe(10_000);
    expect(thresholdOf(thresholds, 'REFUND')).toBe(5_000);
    expect(thresholdOf(null, 'CANCELLATION')).toBe(0);
  });

  it('recognises the refusals that concern the second signature', () => {
    const refusal = (code: string) => ({ error: { code, detail: '…' } });
    expect(isFourEyesRefusal(refusal('four-eyes-required'))).toBe(true);
    expect(isFourEyesRefusal(refusal('four-eyes-same-person'))).toBe(true);
    expect(isFourEyesRefusal(refusal('bad-credentials'))).toBe(true);
    expect(isFourEyesRefusal(refusal('write-off-quantity'))).toBe(false);
    expect(isFourEyesRefusal(new Error('réseau'))).toBe(false);
    expect(problemCode({ error: { code: 'x' } })).toBe('x');
    expect(problemCode(null)).toBeNull();
  });

  it('sends the approver only when both fields are filled in', () => {
    expect(approverFields({ email: ' adjoint@sahel.demo ', pin: '1234' })).toEqual({
      approverEmail: 'adjoint@sahel.demo',
      approverPin: '1234',
    });
    expect(approverFields({ email: 'adjoint@sahel.demo', pin: '' })).toEqual({});
    expect(approverFields(null)).toEqual({});
    expect(approvalComplete({ email: 'a@b.c', pin: '123' })).toBe(false);
    expect(approvalComplete({ email: 'a@b.c', pin: '1234' })).toBe(true);
  });
});
