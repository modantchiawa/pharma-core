import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { APP_ENVIRONMENT } from '../../core/config';

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

/** Shared duty roster: who is on duty now and over the next weeks. */
@Component({
  selector: 'app-guard-page',
  imports: [DatePipe],
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
})
export class GuardPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/guards`;
  protected readonly myTenant = inject(AuthService).tenantId;
  protected readonly now = signal<GuardSlot[]>([]);
  protected readonly slots = signal<GuardSlot[]>([]);
  protected readonly upcoming = computed(() =>
    [...this.slots()].sort((a, b) => a.startsAt.localeCompare(b.startsAt)),
  );

  constructor() {
    void this.load();
  }

  protected label(type: GuardSlot['type']): string {
    return TYPE_LABEL[type];
  }

  private async load(): Promise<void> {
    const from = new Date();
    const to = new Date(from.getTime() + 30 * 24 * 3_600_000);
    const [now, slots] = await Promise.all([
      firstValueFrom(this.http.get<GuardSlot[]>(`${this.api}/now`)),
      firstValueFrom(
        this.http.get<GuardSlot[]>(this.api, {
          params: { from: from.toISOString(), to: to.toISOString() },
        }),
      ),
    ]);
    this.now.set(now);
    this.slots.set(slots);
  }
}
