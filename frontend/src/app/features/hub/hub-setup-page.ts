import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { NodeInfoService } from '../../core/api/node-info.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { Icon } from '../../shared/ui/icon';

/** First-run wizard of a hub: enter the pairing code issued by the editor. */
@Component({
  selector: 'app-hub-setup-page',
  imports: [FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main
      class="setup relative flex min-h-screen items-center justify-center overflow-hidden bg-[var(--app-bg)] p-4"
    >
      <section
        class="card animate-in relative w-full max-w-md p-6 shadow-[var(--shadow-lg)] sm:p-8"
      >
        <div class="mb-6 flex items-center gap-3">
          <span
            class="flex h-11 w-11 items-center justify-center rounded-xl bg-brand text-on-brand shadow-sm"
          >
            <app-icon name="server" [size]="22" />
          </span>
          <div class="leading-tight">
            <p class="text-sm font-semibold">PharmaCore</p>
            <p class="text-xs text-muted">Serveur de l'officine</p>
          </div>
        </div>

        <h1 class="text-xl font-bold">Installation du serveur de l'officine</h1>
        <p class="mt-2 text-sm leading-relaxed text-muted">
          Ce serveur (<code class="rounded bg-surface-3 px-1 py-0.5 text-ink">{{
            node.info()?.nodeId
          }}</code
          >) n'est pas encore rattaché à une pharmacie. Saisissez le code d'appairage fourni par
          PharmaCore. Une connexion Internet est nécessaire pour cette étape uniquement.
        </p>

        <form class="mt-6 space-y-3" (ngSubmit)="pair()">
          <label class="label" for="pairing-code">Code d'appairage</label>
          <input
            id="pairing-code"
            class="input h-14 text-center font-mono text-2xl uppercase tracking-[0.2em]"
            placeholder="XXXX-XXXX"
            maxlength="9"
            [(ngModel)]="code"
            name="code"
            autofocus
            autocomplete="off"
          />
          <button
            type="submit"
            class="btn btn-primary btn-lg w-full"
            [disabled]="busy() || code.length < 8"
          >
            <app-icon
              [name]="busy() ? 'refresh' : 'key'"
              [size]="18"
              [class.animate-spin]="busy()"
            />
            {{ busy() ? 'Appairage…' : 'Appairer' }}
          </button>
        </form>

        @if (error(); as text) {
          <p class="alert alert-danger mt-4" role="alert">
            <app-icon name="error" [size]="18" />
            <span>{{ text }}</span>
          </p>
        }
        @if (done(); as reply) {
          <p class="alert alert-success mt-4">
            <app-icon name="checkCircle" [size]="18" />
            <span>
              Serveur rattaché à <strong>{{ reply.displayName }}</strong
              >. Le chargement initial des données se poursuit en arrière-plan ; vous pouvez vous
              connecter.
            </span>
          </p>
        }

        <p class="mt-6 flex items-center gap-2 border-t border-line pt-4 text-xs text-subtle">
          <app-icon name="lock" [size]="14" />
          Après l'appairage, les caisses de l'officine fonctionnent aussi sans Internet.
        </p>
      </section>
    </main>
  `,
  styles: `
    .setup::before {
      content: '';
      position: absolute;
      inset: -40% -20% auto;
      height: 80%;
      background: radial-gradient(
        closest-side,
        color-mix(in oklab, var(--brand-primary) 18%, transparent),
        transparent
      );
      pointer-events: none;
    }
  `,
})
export class HubSetupPage {
  protected readonly node = inject(NodeInfoService);
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly router = inject(Router);
  protected code = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<{ displayName: string } | null>(null);

  protected async pair(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const reply = await firstValueFrom(
        this.http.post<{ displayName: string }>(`${this.env.apiUrl}/public/hub/pair`, {
          code: this.code.trim(),
        }),
      );
      this.done.set(reply);
      await this.node.load();
      setTimeout(() => void this.router.navigateByUrl('/login'), 2500);
    } catch (error) {
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Appairage impossible',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
