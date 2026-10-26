import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { GuardShareText, NetworkSettings } from '../../core/api/models';
import { NodeInfoService } from '../../core/api/node-info.service';
import { AuthService } from '../../core/auth/auth.service';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { Icon } from '../../shared/ui/icon';

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

const TYPE_BADGE: Record<GuardSlot['type'], string> = {
  NIGHT: 'badge-info',
  SUNDAY: 'badge-brand',
  PUBLIC_HOLIDAY: 'badge-warning',
  EXTENDED_DAY: '',
};

/** Shared duty roster: who is on duty now and over the next weeks, ready to forward to patients. */
@Component({
  selector: 'app-guard-page',
  imports: [DatePipe, FormsModule, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Gardes</h1>
          <p class="page-subtitle">
            Planning de garde partagé du réseau, prêt à transmettre aux patients
          </p>
        </div>
      </header>

      <div class="space-y-5">
        <section class="card on-duty overflow-hidden">
          <div class="flex items-center gap-4 p-5">
            <span
              class="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-brand text-on-brand"
            >
              <app-icon name="guards" [size]="22" />
            </span>
            <div class="min-w-0 flex-1">
              <h2 class="text-xs font-semibold tracking-wider text-muted uppercase">
                De garde maintenant
              </h2>
              @for (slot of now(); track slot.id) {
                <div class="mt-1 flex flex-wrap items-baseline gap-x-4 gap-y-1">
                  <p class="text-xl font-semibold">{{ slot.pharmacyName }}</p>
                  @if (slot.phone) {
                    <span class="inline-flex items-center gap-1.5 text-sm font-medium">
                      <app-icon name="phone" [size]="14" />{{ slot.phone }}
                    </span>
                  }
                  <span class="inline-flex items-center gap-1.5 text-sm text-muted">
                    <app-icon name="clock" [size]="14" />
                    jusqu'à {{ slot.endsAt | date: 'EEE HH:mm' }}
                  </span>
                </div>
              } @empty {
                <p class="mt-1 text-muted">Aucune garde en cours.</p>
              }
            </div>
          </div>
        </section>

        <div
          class="grid items-start gap-5"
          [class]="coordinated().length > 0 ? 'lg:grid-cols-2' : ''"
        >
          <section class="card">
            <div class="card-header">
              <div class="flex items-center gap-3">
                <span class="kpi-icon"><app-icon name="share" [size]="16" /></span>
                <div>
                  <h2 class="card-title">Partager le planning</h2>
                  <p class="text-xs text-muted">Un message prêt à envoyer aux patients</p>
                </div>
              </div>
            </div>
            <div class="card-body space-y-4">
              <form class="grid items-end gap-3 sm:grid-cols-2" (ngSubmit)="share()">
                <label class="block">
                  <span class="label">Jour</span>
                  <input type="date" class="input" [(ngModel)]="shareDate" name="date" required />
                </label>
                <label class="block">
                  <span class="label">Réseau</span>
                  <select class="select" [(ngModel)]="shareNetwork" name="network">
                    <option value="">Tous mes réseaux</option>
                    @for (n of networks(); track n.networkId) {
                      <option [value]="n.networkId">{{ n.name }}</option>
                    }
                  </select>
                </label>
                <button type="submit" class="btn btn-primary sm:col-span-2 sm:justify-self-start">
                  <app-icon name="sparkles" [size]="16" />
                  Préparer le message
                </button>
              </form>
              @if (shareResult(); as s) {
                <div class="rounded-[var(--radius)] border border-line bg-surface-2">
                  <pre
                    class="max-h-72 overflow-auto whitespace-pre-wrap p-4 font-sans text-sm leading-relaxed"
                    >{{ s.text }}</pre>
                  <div class="flex flex-wrap justify-end gap-2 border-t border-line p-3">
                    <button type="button" class="btn btn-secondary btn-sm" (click)="copy(s.text)">
                      <app-icon [name]="copied() ? 'check' : 'copy'" [size]="14" />
                      {{ copied() ? 'Copié' : 'Copier le texte' }}
                    </button>
                    <a
                      class="btn btn-primary btn-sm"
                      [href]="'https://wa.me/?text=' + encode(s.text)"
                      target="_blank"
                      rel="noopener"
                    >
                      <app-icon name="share" [size]="14" />
                      Partager (WhatsApp)
                    </a>
                  </div>
                </div>
              }
              @if (status(); as text) {
                <div class="alert alert-info" role="status">
                  <app-icon name="info" [size]="16" class="mt-0.5" />
                  <span>{{ text }}</span>
                </div>
              }
            </div>
          </section>

          @if (coordinated().length > 0) {
            <section class="card" *appHasPermission="'GUARD_MANAGE'">
              <div class="card-header">
                <div class="flex items-center gap-3">
                  <span class="kpi-icon"><app-icon name="eye" [size]="16" /></span>
                  <h2 class="card-title">Publication publique des gardes</h2>
                </div>
              </div>
              <div class="card-body space-y-3">
                <p class="text-sm text-muted">
                  Quand la publication est active, les patients voient les pharmacies de garde du
                  réseau sur la page publique (<a
                    class="font-medium text-brand underline-offset-2 hover:underline"
                    href="/public"
                    target="_blank"
                    rel="noopener"
                    >/public</a
                  >) sans se connecter.
                </p>
                <ul class="space-y-2">
                  @for (n of coordinated(); track n.networkId) {
                    <li>
                      <label
                        class="flex cursor-pointer items-center gap-3 rounded-[var(--radius)] border border-line px-4 py-3 hover:bg-surface-2"
                      >
                        <input
                          type="checkbox"
                          [checked]="n.publishGuards"
                          (change)="publish(n, $event)"
                        />
                        <span class="min-w-0 flex-1">
                          <span class="block font-medium">{{ n.name }}</span>
                          <span class="block text-xs text-subtle">{{ n.region }}</span>
                        </span>
                        @if (n.publishGuards) {
                          <span class="badge badge-success"><span class="dot"></span>Publiée</span>
                        } @else {
                          <span class="badge">Privée</span>
                        }
                      </label>
                    </li>
                  }
                </ul>
              </div>
            </section>
          }
        </div>

        <section class="card">
          <div class="card-header">
            <div class="flex items-center gap-3">
              <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
              <h2 class="card-title">30 prochains jours</h2>
            </div>
            <span class="badge">{{ upcoming().length }} garde(s)</span>
          </div>
          <div class="overflow-x-auto">
            <table class="data-table">
              <thead class="whitespace-nowrap">
                <tr>
                  <th>Début</th>
                  <th>Fin</th>
                  <th>Type</th>
                  <th>Pharmacie</th>
                </tr>
              </thead>
              <tbody>
                @for (slot of upcoming(); track slot.id) {
                  <tr [class.mine]="slot.pharmacyTenantId === myTenant()">
                    <td class="whitespace-nowrap font-medium capitalize">
                      {{ slot.startsAt | date: 'EEE dd/MM HH:mm' }}
                    </td>
                    <td class="whitespace-nowrap capitalize text-muted">
                      {{ slot.endsAt | date: 'EEE dd/MM HH:mm' }}
                    </td>
                    <td>
                      <span class="badge" [class]="typeBadge(slot.type)">{{
                        label(slot.type)
                      }}</span>
                    </td>
                    <td class="min-w-56">
                      <div class="flex flex-wrap items-center gap-2">
                        <span [class.font-semibold]="slot.pharmacyTenantId === myTenant()">
                          {{ slot.pharmacyName }}
                        </span>
                        @if (slot.pharmacyTenantId === myTenant()) {
                          <span class="badge badge-brand">Vous</span>
                        }
                      </div>
                      <p class="text-xs text-subtle">{{ slot.city }} {{ slot.phone }}</p>
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="4">
                      <div class="empty-state">
                        <app-icon name="calendar" [size]="22" />
                        <p>Aucune garde planifiée sur les 30 prochains jours.</p>
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </div>
  `,
  styles: `
    .on-duty {
      border-color: color-mix(in oklab, var(--brand-primary) 35%, var(--border));
      background: linear-gradient(
        120deg,
        var(--brand-soft),
        var(--brand-softer) 55%,
        var(--surface)
      );
    }
    tr.mine td {
      background: var(--brand-softer);
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

  protected typeBadge(type: GuardSlot['type']): string {
    return TYPE_BADGE[type];
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
