import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SecondApproval } from '../../core/api/models';
import { approvalComplete } from '../four-eyes';
import { MoneyPipe } from '../money.pipe';
import { Icon } from './icon';
import { SecondApprovalFields } from './second-approval';

/** What the dialog hands back: the reason typed and the second person's credentials. */
export interface ApprovalConfirmation {
  reason: string;
  approval: SecondApproval;
}

/**
 * Reusable four-eyes dialog, for actions that have no form of their own (validating a count,
 * retrying a refused request): the reason of the operation and the PIN of a second person.
 */
@Component({
  selector: 'app-approval-dialog',
  imports: [FormsModule, MoneyPipe, Icon, SecondApprovalFields],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'cancelled.emit()' },
  template: `
    <div
      class="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(2_6_12/0.5)] p-0 backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="title()"
    >
      <form
        class="card w-full max-w-md rounded-b-none shadow-[var(--shadow-lg)] animate-in sm:rounded-b-[var(--radius-lg)]"
        (ngSubmit)="submit()"
      >
        <div class="card-header">
          <div class="min-w-0">
            <h3 class="card-title">{{ title() }}</h3>
            @if (detail()) {
              <p class="mt-0.5 text-sm text-muted">{{ detail() }}</p>
            }
          </div>
          @if (amountCents() !== null) {
            <span class="badge shrink-0 tabular-nums">{{ amountCents() | money }}</span>
          }
        </div>
        <div class="card-body space-y-3">
          @if (askReason()) {
            <label class="block">
              <span class="label">Motif</span>
              <input
                class="input"
                name="reason"
                maxlength="255"
                required
                [ngModel]="reason()"
                (ngModelChange)="reason.set($event)"
              />
            </label>
          }
          <app-second-approval [why]="why()" [(email)]="email" [(pin)]="pin" />
          @if (error()) {
            <p class="flex items-start gap-1.5 text-sm text-danger" role="alert">
              <app-icon name="alert" [size]="15" class="mt-0.5" />{{ error() }}
            </p>
          }
          <div class="flex flex-col-reverse gap-2 pt-1 sm:flex-row sm:justify-end">
            <button type="button" class="btn btn-secondary" (click)="cancelled.emit()">
              Annuler
            </button>
            <button type="submit" class="btn btn-primary" [disabled]="!ready() || busy()">
              <app-icon name="check" [size]="16" />
              {{ confirmLabel() }}
            </button>
          </div>
        </div>
      </form>
    </div>
  `,
})
export class ApprovalDialog {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  readonly title = input.required<string>();
  readonly detail = input<string | null>(null);
  readonly why = input('Opération sensible');
  readonly amountCents = input<number | null>(null);
  readonly askReason = input(true);
  readonly initialReason = input('');
  readonly confirmLabel = input('Valider');
  readonly busy = input(false);
  readonly error = input<string | null>(null);

  readonly confirmed = output<ApprovalConfirmation>();
  readonly cancelled = output<void>();

  protected readonly reason = signal('');
  protected readonly email = signal('');
  protected readonly pin = signal('');
  protected readonly ready = computed(
    () =>
      (!this.askReason() || this.reason().trim().length > 0) &&
      approvalComplete({ email: this.email(), pin: this.pin() }),
  );

  constructor() {
    afterNextRender(() => {
      this.reason.set(this.initialReason());
      this.host.nativeElement.querySelector<HTMLInputElement>('input')?.focus();
    });
  }

  protected submit(): void {
    if (this.ready() && !this.busy()) {
      this.confirmed.emit({
        reason: this.reason().trim(),
        approval: { email: this.email().trim(), pin: this.pin().trim() },
      });
    }
  }
}
