import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { GuardShareText, NetworkSettings } from '../../core/api/models';
import { NodeInfoService } from '../../core/api/node-info.service';
import { AuthService } from '../../core/auth/auth.service';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';

interface GuardSlot {
  id: string;
  networkId: string;
  pharmacyTenantId: string;
  pharmacyName: string | null;
  city: string | null;
  phone: string | null;
  type: 'NIGHT' | 'SUNDAY' | 'PUBLIC_HOLIDAY' | 'EXTENDED_DAY';
  startsAt: string;
  endsAt: string;
  notes: string | null;
}

const TYPE_LABEL: Record<GuardSlot['type'], string> = {
  NIGHT: 'Nuit',
  SUNDAY: 'Dimanche',
  PUBLIC_HOLIDAY: 'Jour férié',
  EXTENDED_DAY: 'Journée étendue',
};

/** Shared duty roster: who is on duty now and over the next weeks, ready to forward to patients. */
@Component({
  selector: 'app-guard-page',
  imports: [DatePipe, FormsModule, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <section class="rounded-lg border-2 border-brand bg-white p-4">
        <h2 class="text-lg font-semibold">De garde maintenant</h2>
        @for (slot of now(); track slot.id) {
          <p class="text-xl">
            {{ slot.pharmacyName }} · {{ slot.phone }}
            <small class="text-slate-500">jusqu'à {{ slot.endsAt | date: 'EEE HH:mm' }}</small>
          </p>
        } @empty {
          <p class="text-slate-500">Aucune garde en cours.</p>
        }
      </section>

      <section class="rounded-lg border bg-white p-4">
        <h2 class="mb-2 text-lg font-semibold">Partager le planning</h2>
        <form class="flex flex-wrap items-end gap-2" (ngSubmit)="share()">
          <label class="text-sm"
            >Jour
            <input type="date" class="field" [(ngModel)]="shareDate" name="date" required />
          </label>
          <label class="text-sm"
            >Réseau
            <select class="field" [(ngModel)]="shareNetwork" name="network">
              <option value="">Tous mes réseaux</option>
              @for (n of networks(); track n.networkId) {
                <option [value]="n.networkId">{{ n.name }}</option>
              }
            </select>
          </label>
          <button type="submit" class="rounded bg-brand px-3 py-2 text-on-brand">
            Préparer le message
          </button>
          @if (shareResult(); as s) {
            <a
              class="rounded border px-3 py-2"
              [href]="'https://wa.me/?text=' + encode(s.text)"
              target="_blank"
              rel="noopener"
              >Partager (WhatsApp)</a
            >
            <button type="button" class="rounded border px-3 py-2" (click)="copy(s.text)">
              {{ copied() ? 'Copié' : 'Copier le texte' }}
            </button>
          }
        </form>
        @if (shareResult(); as s) {
          <pre class="mt-2 whitespace-pre-wrap rounded bg-slate-100 p-3 text-sm">{{ s.text }}</pre>
        }
        @if (status(); as text) {
          <p class="mt-2 text-sm" role="status">{{ text }}</p>
        }
      </section>

      @if (coordinated().length > 0) {
        <section class="rounded-lg border bg-white p-4" *appHasPermission="'GUARD_MANAGE'">
          <h2 class="mb-2 text-lg font-semibold">Publication publique des gardes</h2>
          <p class="mb-2 text-sm text-slate-600">
            Quand la publication est active, les patients voient les pharmacies de garde du réseau
            sur la page publique (<a class="underline" href="/public" target="_blank" rel="noopener"
              >/public</a
            >) sans se connecter.
          </p>
          @for (n of coordinated(); track n.networkId) {
            <label class="flex items-center gap-2 py-1">
              <input type="checkbox" [checked]="n.publishGuards" (change)="publish(n, $event)" />
              {{ n.name }} <small class="text-slate-500">{{ n.region }}</small>
            </label>
          }
        </section>
      }

      <section class="rounded-lg border bg-white p-4">
        <h2 class="mb-2 text-lg font-semibold">30 prochains jours</h2>
        <table class="w-full text-sm">
          <thead class="text-left">
            <tr>
              <th class="p-2">Début</th>
              <th class="p-2">Fin</th>
              <th class="p-2">Type</th>
              <th class="p-2">Pharmacie</th>
            </tr>
          </thead>
          <tbody>
            @for (slot of upcoming(); track slot.id) {
              <tr class="border-t" [class.font-semibold]="slot.pharmacyTenantId === myTenant()">
                <td class="p-2">{{ slot.startsAt | date: 'EEE dd/MM HH:mm' }}</td>
                <td class="p-2">{{ slot.endsAt | date: 'EEE dd/MM HH:mm' }}</td>
                <td class="p-2">{{ label(slot.type) }}</td>
                <td class="p-2">
                  {{ slot.pharmacyName }}
                  <small class="text-slate-500">{{ slot.city }} {{ slot.phone }}</small>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    </div>
  `,
  styles: `
    .field {
      display: block;
      margin-top: 0.25rem;
      border: 1px solid #cbd5e1;
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
  `,
})
export class GuardPage {
  private readonly http = inject(HttpClient);
  private readonly networkApi = inject(NodeInfoService).networkApiUrl();
  private readonly api = `${this.networkApi}/v1/guards`;
  protected readonly myTenant = inject(AuthService).tenantId;
  protected readonly now = signal<GuardSlot[]>([]);
  protected readonly slots = signal<GuardSlot[]>([]);
  protected readonly networks = signal<NetworkSettings[]>([]);
  protected readonly coordinated = computed(() => this.networks().filter((n) => n.coordinator));
  protected readonly shareResult = signal<GuardShareText | null>(null);
  protected readonly copied = signal(false);
  protected readonly status = signal<string | null>(null);
  protected readonly upcoming = computed(() =>
    [...this.slots()].sort((a, b) => a.startsAt.localeCompare(b.startsAt)),
  );
  protected shareDate = new Date().toISOString().slice(0, 10);
  protected shareNetwork = '';

  constructor() {
    void this.load();
  }

  protected label(type: GuardSlot['type']): string {
    return TYPE_LABEL[type];
  }

  protected async share(): Promise<void> {
    this.status.set(null);
    try {
      const params: Record<string, string> = { date: this.shareDate };
      if (this.shareNetwork) {
        params['networkId'] = this.shareNetwork;
      }
      this.shareResult.set(
        await firstValueFrom(this.http.get<GuardShareText>(`${this.api}/share-text`, { params })),
      );
      this.copied.set(false);
    } catch {
      this.status.set('Planning indisponible (hors-ligne ?)');
    }
  }

  protected async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.copied.set(true);
    } catch {
      this.copied.set(false);
    }
  }

  protected encode(text: string): string {
    return encodeURIComponent(text);
  }

  protected async publish(network: NetworkSettings, event: Event): Promise<void> {
    const publish = (event.target as HTMLInputElement).checked;
    try {
      const updated = await firstValueFrom(
        this.http.put<NetworkSettings>(
          `${this.networkApi}/v1/network/${network.networkId}/guards-publication`,
          { publish },
        ),
      );
      this.networks.update((items) =>
        items.map((n) => (n.networkId === updated.networkId ? updated : n)),
      );
      this.status.set(
        publish ? 'Gardes publiées sur la page publique' : 'Publication des gardes désactivée',
      );
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Modification refusée',
      );
      (event.target as HTMLInputElement).checked = network.publishGuards;
    }
  }

  private async load(): Promise<void> {
    const from = new Date();
    const to = new Date(from.getTime() + 30 * 24 * 3_600_000);
    const [now, slots, networks] = await Promise.all([
      firstValueFrom(this.http.get<GuardSlot[]>(`${this.api}/now`)),
      firstValueFrom(
        this.http.get<GuardSlot[]>(this.api, {
          params: { from: from.toISOString(), to: to.toISOString() },
        }),
      ),
      firstValueFrom(
        this.http.get<NetworkSettings[]>(`${this.networkApi}/v1/network/networks`),
      ).catch(() => [] as NetworkSettings[]),
    ]);
    this.now.set(now);
    this.slots.set(slots);
    this.networks.set(networks);
  }
}
