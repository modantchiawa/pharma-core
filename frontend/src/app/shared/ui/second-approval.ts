import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icon } from './icon';

/**
 * Inline "four eyes" fields: a second authorised person types their e-mail and PIN on this
 * terminal, right in the action's own form (same gesture as the supervisor override at the
 * counter), so nobody waits for an approval queue.
 */
@Component({
  selector: 'app-second-approval',
  imports: [FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="approval" role="group" aria-label="Seconde validation">
      <div class="flex items-start gap-2.5">
        <span class="approval-icon"><app-icon name="lock" [size]="16" /></span>
        <div class="min-w-0 text-sm">
          <p class="font-semibold text-ink">Seconde validation</p>
          <p class="text-muted">
            {{ why() }} : une autre personne habilitée (titulaire ou adjoint) saisit son e-mail et
            son code PIN sur ce poste.
          </p>
        </div>
      </div>
      <div class="mt-3 grid gap-2 sm:grid-cols-[minmax(0,1fr)_8rem]">
        <label class="block">
          <span class="label">E-mail de la seconde personne</span>
          <input
            class="input"
            type="email"
            autocomplete="off"
            data-testid="approver-email"
            [ngModel]="email()"
            (ngModelChange)="email.set($event)"
            [ngModelOptions]="{ standalone: true }"
          />
        </label>
        <label class="block">
          <span class="label">Son code PIN</span>
          <input
            class="input tabular-nums"
            type="password"
            inputmode="numeric"
            autocomplete="off"
            maxlength="12"
            data-testid="approver-pin"
            [ngModel]="pin()"
            (ngModelChange)="pin.set($event)"
            [ngModelOptions]="{ standalone: true }"
          />
        </label>
      </div>
    </div>
  `,
  styles: `
    .approval {
      padding: 0.85rem;
      border: 1px solid color-mix(in srgb, var(--warning) 35%, transparent);
      border-radius: var(--radius);
      background: var(--warning-soft);
    }
    .approval-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2rem;
      height: 2rem;
      flex-shrink: 0;
      border-radius: 999px;
      background: var(--surface);
      color: var(--warning);
    }
  `,
})
export class SecondApprovalFields {
  /** Why the second signature is needed ("Montant au-delà de 10 000 F CFA", "Stupéfiant"). */
  readonly why = input('Opération sensible');
  readonly email = model('');
  readonly pin = model('');
}
