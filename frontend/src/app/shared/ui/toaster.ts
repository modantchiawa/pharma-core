import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from '../../core/ui/toast.service';
import { Icon } from './icon';

/**
 * Stack of toasts at the bottom center, above everything: clear of the POS scan field (top),
 * of the validation button and of the save buttons (bottom right).
 */
@Component({
  selector: 'app-toaster',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toaster print:hidden" aria-live="polite" aria-atomic="false">
      @for (toast of service.toasts(); track toast.id) {
        <div class="toast animate-in" [attr.data-kind]="toast.kind" role="status">
          <app-icon
            [name]="
              toast.kind === 'success' ? 'checkCircle' : toast.kind === 'error' ? 'error' : 'info'
            "
            [size]="18"
            class="toast-icon"
          />
          <p class="min-w-0 flex-1 text-sm">{{ toast.message }}</p>
          <button
            type="button"
            class="btn btn-ghost btn-icon btn-sm -my-1 -mr-1.5"
            aria-label="Fermer"
            (click)="service.dismiss(toast.id)"
          >
            <app-icon name="x" [size]="14" />
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .toaster {
      position: fixed;
      z-index: 60;
      bottom: 1.25rem;
      left: 50%;
      transform: translateX(-50%);
      display: flex;
      flex-direction: column-reverse;
      align-items: center;
      gap: 0.5rem;
      width: min(26rem, calc(100vw - 2rem));
      pointer-events: none;
    }
    .toast {
      pointer-events: auto;
      display: flex;
      align-items: flex-start;
      gap: 0.65rem;
      width: 100%;
      padding: 0.75rem 0.9rem;
      border: 1px solid var(--border);
      border-left-width: 3px;
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
      box-shadow: var(--shadow-lg);
    }
    .toast[data-kind='success'] {
      border-left-color: var(--success);
    }
    .toast[data-kind='success'] .toast-icon {
      color: var(--success);
    }
    .toast[data-kind='error'] {
      border-left-color: var(--danger-strong);
    }
    .toast[data-kind='error'] .toast-icon {
      color: var(--danger-strong);
    }
    .toast[data-kind='info'] {
      border-left-color: var(--info);
    }
    .toast[data-kind='info'] .toast-icon {
      color: var(--info);
    }
    .toast-icon {
      margin-top: 0.05rem;
    }
  `,
})
export class Toaster {
  protected readonly service = inject(ToastService);
}
