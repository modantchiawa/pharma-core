import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { NodeInfoService } from '../../core/api/node-info.service';
import { APP_ENVIRONMENT } from '../../core/config';

/** First-run wizard of a hub: enter the pairing code issued by the editor. */
@Component({
  selector: 'app-hub-setup-page',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="flex min-h-screen items-center justify-center bg-brand-dark p-4">
      <section class="w-full max-w-md space-y-4 rounded-xl bg-white p-6 shadow-xl">
        <h1 class="text-xl font-bold text-brand">Installation du serveur de l'officine</h1>
        <p class="text-sm text-slate-600">
          Ce serveur (<code>{{ node.info()?.nodeId }}</code
          >) n'est pas encore rattaché à une pharmacie. Saisissez le code d'appairage fourni par
          PharmaCore. Une connexion Internet est nécessaire pour cette étape uniquement.
        </p>
        <form class="space-y-3" (ngSubmit)="pair()">
          <input
            class="w-full rounded border px-3 py-3 text-center text-2xl uppercase tracking-widest"
            placeholder="XXXX-XXXX"
            maxlength="9"
            [(ngModel)]="code"
            name="code"
            autofocus
          />
          <button
            type="submit"
            class="w-full rounded-lg bg-brand py-3 font-semibold text-on-brand"
            [disabled]="busy() || code.length < 8"
          >
            {{ busy() ? 'Appairage…' : 'Appairer' }}
          </button>
        </form>
        @if (error(); as text) {
          <p class="rounded bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">{{ text }}</p>
        }
        @if (done(); as reply) {
          <p class="rounded bg-green-50 px-3 py-2 text-sm text-green-800">
            Serveur rattaché à <strong>{{ reply.displayName }}</strong
            >. Le chargement initial des données se poursuit en arrière-plan ; vous pouvez vous
            connecter.
          </p>
        }
      </section>
    </main>
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
