import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('shows a toast and hides it on its own', () => {
    const toasts = new ToastService();
    toasts.success('Logo mis à jour');
    expect(toasts.toasts()).toEqual([{ id: 1, kind: 'success', message: 'Logo mis à jour' }]);
    vi.advanceTimersByTime(3500);
    expect(toasts.toasts()).toEqual([]);
  });

  it('keeps errors on screen longer than confirmations', () => {
    const toasts = new ToastService();
    toasts.success('Enregistré');
    toasts.error('Réseau indisponible');
    vi.advanceTimersByTime(4000);
    expect(toasts.toasts().map((t) => t.kind)).toEqual(['error']);
  });

  it('keeps only the four most recent toasts and can be dismissed', () => {
    const toasts = new ToastService();
    for (let i = 1; i <= 6; i++) {
      toasts.info(`Message ${i}`);
    }
    expect(toasts.toasts().map((t) => t.message)).toEqual([
      'Message 3',
      'Message 4',
      'Message 5',
      'Message 6',
    ]);
    toasts.dismiss(toasts.toasts()[0].id);
    expect(toasts.toasts()).toHaveLength(3);
  });
});
