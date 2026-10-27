import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  viewChild,
} from '@angular/core';
import { TOAST_DURATION_MS, ToastService } from '../../core/ui/toast.service';
import { withExits } from './exit-list';
import { Icon } from './icon';
import { DURATION, EASING, prefersReducedMotion } from './motion';

/**
 * Stack of toasts at the bottom center, above everything: clear of the POS scan field (top),
 * of the validation button and of the save buttons (bottom right). Toasts rise in, fade out,
 * show the time left as a thin bar, and the others glide to their new place (FLIP) instead of
 * jumping when one leaves.
 */
@Component({
  selector: 'app-toaster',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toaster print:hidden" aria-live="polite" aria-atomic="false" #stack>
      @for (entry of shown(); track entry.item.id) {
        @let toast = entry.item;
        <div
          class="toast"
          [class.toast-leave]="entry.leaving"
          [attr.data-kind]="toast.kind"
          role="status"
        >
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
          <span
            class="toast-time"
            aria-hidden="true"
            [style.animation-duration.ms]="durations[toast.kind]"
          ></span>
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
      position: relative;
      overflow: hidden;
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
      border-left-color: var(--toast-color);
      box-shadow: var(--shadow-lg);
      --toast-color: var(--info);
      animation: toast-in var(--motion-long) var(--ease-decelerate);
    }
    .toast[data-kind='success'] {
      --toast-color: var(--success);
    }
    .toast[data-kind='error'] {
      --toast-color: var(--danger-strong);
    }
    .toast-icon {
      margin-top: 0.05rem;
      color: var(--toast-color);
    }
    .toast-time {
      position: absolute;
      left: 0;
      right: 0;
      bottom: 0;
      height: 2px;
      background: var(--toast-color);
      opacity: 0.45;
      transform-origin: left;
      animation: toast-time linear forwards;
    }
    .toast.toast-leave {
      animation: toast-out var(--motion-medium) var(--ease-accelerate) forwards;
    }
    @keyframes toast-in {
      from {
        opacity: 0;
        transform: translateY(14px) scale(0.96);
      }
    }
    @keyframes toast-out {
      to {
        opacity: 0;
        transform: translateY(8px) scale(0.96);
      }
    }
    @keyframes toast-time {
      from {
        transform: scaleX(1);
      }
      to {
        transform: scaleX(0);
      }
    }
  `,
})
export class Toaster {
  protected readonly service = inject(ToastService);
  protected readonly durations = TOAST_DURATION_MS;
  /** The service's toasts, plus those playing their exit until it ends. */
  protected readonly shown = withExits(
    () => this.service.toasts(),
    (t) => t.id,
  );
  private readonly stack = viewChild.required<ElementRef<HTMLElement>>('stack');

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const container = this.stack().nativeElement;
      const tops = new WeakMap<Element, number>();
      // Layout position on screen, without the transform of an animation in progress
      const top = (child: HTMLElement) => container.getBoundingClientRect().top + child.offsetTop;
      const children = () => Array.from(container.children) as HTMLElement[];
      const measure = () => children().forEach((child) => tops.set(child, top(child)));
      // FLIP: after any toast arrives or is removed, the others start from where they were
      const observer = new MutationObserver(() => {
        if (prefersReducedMotion()) {
          measure();
          return;
        }
        for (const child of children()) {
          const before = tops.get(child);
          const delta = before === undefined ? 0 : before - top(child);
          if (Math.abs(delta) > 0.5 && !child.classList.contains('toast-leave')) {
            child.animate([{ transform: `translateY(${delta}px)` }, { transform: 'none' }], {
              duration: DURATION.long,
              easing: EASING.emphasized,
            });
          }
        }
        measure();
      });
      observer.observe(container, { childList: true });
      destroyRef.onDestroy(() => observer.disconnect());
    });
  }
}
