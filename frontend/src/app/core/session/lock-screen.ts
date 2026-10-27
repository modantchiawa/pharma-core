import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icon } from '../../shared/ui/icon';
import { NodeInfoService } from '../api/node-info.service';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { BrandingService } from '../branding/branding.service';
import { TerminalService } from '../offline/terminal.service';
import { ToastService } from '../ui/toast.service';
import { MAX_UNLOCK_FAILURES, idleDuration, minutesBeforeLogout, warningProgress } from './idle';
import { IdleService } from './idle.service';
import { UnlockVerifier } from './unlock-verifier';

type Mode = 'pin' | 'password' | 'switch';

/** Circumference of the countdown ring (r = 18). */
const RING = 2 * Math.PI * 18;

/**
 * Warning 30 seconds before the lock (a ring that empties, "Je suis là"), then the lock screen:
 * the page stays as it was (an open basket included) under a blur that hides its data, until
 * the user types their PIN or password, or another member of the staff switches in with theirs.
 */
@Component({
  selector: 'app-lock-screen',
  imports: [FormsModule, DatePipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (warning(); as w) {
      <aside
        class="idle-warning animate-in print:hidden"
        role="alertdialog"
        aria-labelledby="idle-warning-title"
        aria-describedby="idle-warning-text"
        data-testid="idle-warning"
      >
        <span class="countdown" aria-hidden="true">
          <svg viewBox="0 0 44 44">
            <circle class="countdown-track" cx="22" cy="22" r="18" />
            <circle
              class="countdown-value"
              cx="22"
              cy="22"
              r="18"
              [attr.stroke-dasharray]="ring"
              [attr.stroke-dashoffset]="ring * (1 - w.progress)"
            />
          </svg>
          <span class="countdown-seconds">{{ w.seconds }}</span>
        </span>
        <div class="min-w-0 flex-1">
          <p id="idle-warning-title" class="text-sm font-semibold">
            {{ w.next === 'lock' ? 'Verrouillage' : 'Fermeture de la session' }} dans
            {{ w.seconds }} s
          </p>
          <p id="idle-warning-text" class="text-xs text-muted">
            Aucune activité depuis {{ w.idle }}
            {{ w.next === 'lock' ? '· la page reste en l’état' : '' }}
          </p>
        </div>
        <button type="button" class="btn btn-primary btn-sm" (click)="idle.stillHere()">
          Je suis là
        </button>
      </aside>
    }

    @if (idle.locked()) {
      <div
        class="lock-backdrop print:hidden"
        role="dialog"
        aria-modal="true"
        aria-labelledby="lock-title"
        data-testid="lock-screen"
        #backdrop
        (keydown)="$event.stopPropagation()"
      >
        <form class="lock-card animate-in" (ngSubmit)="submit()" autocomplete="off">
          <div class="flex items-center justify-center gap-2 text-xs font-medium text-muted">
            @if (branding.logoUrl(); as logo) {
              <img [src]="logo" alt="" class="h-5 w-5 rounded object-contain" />
            }
            <span class="truncate">{{ branding.branding().displayName }}</span>
          </div>

          <div class="avatar" aria-hidden="true">
            {{ initials(mode() === 'switch' ? switchName() : userName()) }}
            <span class="avatar-lock"><app-icon name="lock" [size]="13" [stroke]="2.25" /></span>
          </div>
          <h2 id="lock-title" class="text-xl font-bold tracking-tight">
            {{ mode() === 'switch' ? 'Changer d’utilisateur' : 'Session verrouillée' }}
          </h2>
          <p class="mt-1 text-sm text-muted">
            @if (mode() === 'switch') {
              Le panier en cours est conservé pour la personne qui se connecte.
            } @else {
              {{ userName() }} · verrouillée à {{ idle.lockedSince() | date: 'HH:mm' }}
            }
          </p>

          @if (mode() === 'switch') {
            <label class="mt-5 block text-left">
              <span class="label">Utilisateur</span>
              <select class="select" name="switchEmail" [(ngModel)]="switchEmail">
                @for (u of colleagues(); track u.email) {
                  <option [value]="u.email">{{ u.name }}</option>
                }
              </select>
            </label>
          } @else if (pinAvailable()) {
            <div class="segmented mt-5 w-full" role="tablist" aria-label="Moyen de déverrouillage">
              <button
                type="button"
                role="tab"
                class="flex-1"
                [attr.aria-selected]="mode() === 'pin'"
                (click)="choose('pin')"
              >
                Code PIN
              </button>
              <button
                type="button"
                role="tab"
                class="flex-1"
                [attr.aria-selected]="mode() === 'password'"
                (click)="choose('password')"
              >
                Mot de passe
              </button>
            </div>
          }

          <label class="mt-4 block text-left">
            <span class="label">{{ mode() === 'password' ? 'Mot de passe' : 'Code PIN' }}</span>
            <input
              #secretInput
              class="input secret"
              [class.secret-pin]="mode() !== 'password'"
              name="secret"
              type="password"
              [attr.inputmode]="mode() === 'password' ? null : 'numeric'"
              [attr.maxlength]="mode() === 'password' ? 200 : 6"
              [attr.aria-label]="mode() === 'password' ? 'Mot de passe' : 'Code PIN'"
              [attr.aria-invalid]="!!error()"
              autocomplete="off"
              [(ngModel)]="secret"
              [disabled]="busy()"
            />
          </label>

          @if (error(); as message) {
            <p class="alert alert-danger mt-3 text-left" role="alert">
              <app-icon name="error" [size]="16" class="mt-0.5" />
              <span>{{ message }}</span>
            </p>
          }

          <button
            type="submit"
            class="btn btn-primary btn-lg mt-4 w-full"
            [disabled]="!secret || busy()"
          >
            <app-icon [name]="mode() === 'switch' ? 'switchUser' : 'lockOpen'" [size]="18" />
            {{ mode() === 'switch' ? 'Se connecter' : 'Déverrouiller' }}
          </button>

          <p class="mt-3 flex items-center justify-center gap-1.5 text-xs text-subtle">
            <app-icon name="timer" [size]="13" />
            Fermeture automatique de la session dans {{ minutesLeft() }} min
          </p>

          <div class="mt-4 flex flex-wrap justify-center gap-1 border-t border-line pt-3">
            @if (mode() === 'switch') {
              <button type="button" class="btn btn-ghost btn-sm" (click)="choose(defaultMode())">
                <app-icon name="chevronLeft" [size]="15" />
                {{ userName() }}
              </button>
            } @else if (colleagues().length > 0 && pinAvailable()) {
              <button type="button" class="btn btn-ghost btn-sm" (click)="choose('switch')">
                <app-icon name="switchUser" [size]="15" />
                Changer d’utilisateur
              </button>
            }
            <button
              type="button"
              class="btn btn-ghost btn-sm text-[var(--danger-strong)]"
              (click)="signOut()"
            >
              <app-icon name="logout" [size]="15" />
              Se déconnecter
            </button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .idle-warning {
      position: fixed;
      right: 1rem;
      bottom: 1rem;
      z-index: 70;
      display: flex;
      align-items: center;
      gap: 0.85rem;
      width: min(26rem, calc(100vw - 2rem));
      padding: 0.75rem 0.85rem;
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
    }
    .countdown {
      position: relative;
      display: inline-flex;
      flex-shrink: 0;
      width: 2.75rem;
      height: 2.75rem;
    }
    .countdown svg {
      width: 100%;
      height: 100%;
      transform: rotate(-90deg);
    }
    .countdown-track {
      fill: none;
      stroke: var(--surface-3);
      stroke-width: 4;
    }
    .countdown-value {
      fill: none;
      stroke: var(--brand-primary);
      stroke-width: 4;
      stroke-linecap: round;
      transition: stroke-dashoffset 900ms linear;
    }
    .countdown-seconds {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.8rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    .lock-backdrop {
      position: fixed;
      inset: 0;
      z-index: 80;
      display: flex;
      align-items: center;
      justify-content: center;
      overflow-y: auto;
      padding: 1rem;
      background: color-mix(in oklab, var(--app-bg) 62%, transparent);
      backdrop-filter: blur(22px) saturate(0.7);
      -webkit-backdrop-filter: blur(22px) saturate(0.7);
    }
    .lock-card {
      width: min(23rem, 100%);
      padding: 1.5rem 1.5rem 1rem;
      border: 1px solid var(--border);
      border-radius: calc(var(--radius-lg) * 1.25);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
      text-align: center;
    }
    .avatar {
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 4.25rem;
      height: 4.25rem;
      margin: 1.1rem auto 0.85rem;
      border-radius: 999px;
      background: var(--brand-soft);
      color: var(--brand-text);
      font-size: 1.35rem;
      font-weight: 700;
    }
    .avatar-lock {
      position: absolute;
      right: -0.15rem;
      bottom: -0.15rem;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.6rem;
      height: 1.6rem;
      border: 3px solid var(--surface);
      border-radius: 999px;
      background: var(--brand-primary);
      color: var(--brand-on-primary);
    }
    .secret {
      height: 3rem;
      font-size: 1.05rem;
      text-align: center;
    }
    .secret-pin {
      font-size: 1.5rem;
      letter-spacing: 0.5em;
      padding-left: 1.25rem;
    }
  `,
})
export class LockScreen {
  protected readonly idle = inject(IdleService);
  protected readonly branding = inject(BrandingService);
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly node = inject(NodeInfoService);
  private readonly terminal = inject(TerminalService);
  private readonly verifier = new UnlockVerifier();
  private readonly toasts = inject(ToastService);
  private readonly secretInput = viewChild<ElementRef<HTMLInputElement>>('secretInput');
  private readonly backdrop = viewChild<ElementRef<HTMLElement>>('backdrop');

  protected readonly ring = RING;
  protected readonly pinAvailable = computed(() => this.auth.terminalBound());
  protected readonly defaultMode = computed<Mode>(() => (this.pinAvailable() ? 'pin' : 'password'));
  protected readonly mode = signal<Mode>('pin');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly userName = computed(() => this.permissions.profile()?.displayName ?? '');
  protected readonly colleagues = computed(() => {
    this.idle.locked();
    const current = this.auth.currentEmail();
    return this.auth.knownUsers().filter((u) => u.email !== current);
  });
  protected readonly warning = computed(() => {
    const state = this.idle.state();
    if (state.phase !== 'warning' || this.idle.locked()) {
      return null;
    }
    const now = this.idle.clock();
    return {
      next: state.next,
      seconds: state.secondsLeft ?? 0,
      progress: warningProgress(state.secondsLeft),
      idle: idleDuration(now, this.idle.lastActivityAt()),
    };
  });
  protected readonly minutesLeft = computed(() =>
    minutesBeforeLogout(this.idle.clock(), this.idle.lastActivityAt(), this.idle.policy()),
  );
  protected secret = '';
  protected switchEmail = '';

  constructor() {
    // While locked, no key reaches the page underneath (the counter's F9 or Esc, the palette)
    const guard = (event: KeyboardEvent) => {
      if (this.idle.locked() && !this.backdrop()?.nativeElement.contains(event.target as Node)) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    window.addEventListener('keydown', guard, true);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('keydown', guard, true));
    // A fresh lock starts on the user's own code, the field ready for typing
    effect(() => {
      if (this.idle.locked()) {
        this.mode.set(this.defaultMode());
        this.error.set(null);
        this.secret = '';
        setTimeout(() => this.secretInput()?.nativeElement.focus());
      }
    });
  }

  protected switchName(): string {
    return this.colleagues().find((u) => u.email === this.switchEmail)?.name ?? '';
  }

  protected choose(mode: Mode): void {
    this.mode.set(mode);
    this.error.set(null);
    this.secret = '';
    if (mode === 'switch') {
      this.switchEmail = this.colleagues()[0]?.email ?? '';
    }
    setTimeout(() => this.secretInput()?.nativeElement.focus());
  }

  protected async submit(): Promise<void> {
    const secret = this.secret;
    const mode = this.mode();
    const email = mode === 'switch' ? this.switchEmail : this.auth.currentEmail();
    if (!secret || !email || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.secret = '';
    const previousUser = this.permissions.profile()?.displayName;
    try {
      await this.auth.replaceSession(() =>
        mode === 'password'
          ? this.auth.loginWithPassword(
              this.node.isHub() ? null : this.auth.lastPharmacy(),
              email,
              secret,
              this.terminal.terminalId,
            )
          : this.auth.loginWithPin(email, secret, this.terminal.terminalId),
      );
      this.idle.unlocked();
      if (mode === 'switch') {
        void this.branding.load();
        this.toasts.success(
          `${this.userName()} prend la suite de ${previousUser ?? 'la session'} : le panier est conservé`,
        );
      }
    } catch (error) {
      await this.refused(error, mode, secret);
    } finally {
      this.busy.set(false);
      setTimeout(() => this.secretInput()?.nativeElement.focus());
    }
  }

  protected async signOut(): Promise<void> {
    await this.idle.signOut();
  }

  private async refused(error: unknown, mode: Mode, secret: string): Promise<void> {
    if (unreachable(error)) {
      const userId = this.permissions.profile()?.userId;
      if (mode !== 'switch' && userId && (await this.verifier.verify(userId, secret))) {
        this.idle.unlocked();
        this.toasts.info('Serveur injoignable : code vérifié sur ce poste');
        return;
      }
      if (mode === 'switch') {
        this.error.set(
          'Serveur injoignable : le changement d’utilisateur attend le retour du réseau',
        );
        return;
      }
      if (!userId || !this.verifier.available(userId)) {
        this.error.set(
          'Serveur injoignable : utilisez le code déjà saisi sur ce poste ou attendez le réseau',
        );
        return;
      }
    }
    await this.idle.unlockFailed();
    const left = this.idle.attemptsLeft();
    const detail = (error as { error?: { detail?: string; code?: string } }).error;
    const reason =
      detail?.code === 'account-locked'
        ? detail.detail
        : mode === 'password'
          ? 'Mot de passe incorrect'
          : 'Code PIN incorrect';
    this.error.set(
      left > 0 && left < MAX_UNLOCK_FAILURES
        ? `${reason} · ${left} essai${left > 1 ? 's' : ''} avant la fermeture de la session`
        : (reason ?? 'Code refusé'),
    );
  }

  protected initials(name: string): string {
    const words = name
      .replace(/^(Dr\.?|M\.|Mme)\s+/i, '')
      .split(/\s+/)
      .filter(Boolean);
    return ((words[0]?.[0] ?? '?') + (words[1]?.[0] ?? '')).toUpperCase();
  }
}

/** No answer from the node (offline, hub down, proxy error): the lock screen checks locally. */
function unreachable(error: unknown): boolean {
  return error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 502);
}
