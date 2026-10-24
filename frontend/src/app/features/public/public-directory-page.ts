import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PublicGuardSlot, PublicPharmacy, PublicProductAvailability } from '../../core/api/models';
import { NodeInfoService } from '../../core/api/node-info.service';

const TYPE_LABEL: Record<PublicGuardSlot['type'], string> = {
  NIGHT: 'Nuit',
  SUNDAY: 'Dimanche',
  PUBLIC_HOLIDAY: 'Jour férié',
  EXTENDED_DAY: 'Journée étendue',
};

/**
 * Patient-facing page, no account needed: where to find a medicine tonight and which
 * pharmacies are on duty. Served by the cloud; large controls for a phone held in one hand.
 */
@Component({
  selector: 'app-public-directory-page',
  imports: [FormsModule, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="min-h-screen bg-slate-50 text-slate-900">
      <header class="bg-brand px-4 py-4 text-on-brand">
        <h1 class="text-xl font-bold">Trouver un médicament, une pharmacie de garde</h1>
        <p class="text-sm opacity-90">
          Disponibilités déclarées par les pharmacies. Appelez avant de vous déplacer.
        </p>
      </header>

      <div class="mx-auto max-w-2xl space-y-6 p-4">
        <form class="space-y-2 rounded-xl border bg-white p-4 shadow-sm" (ngSubmit)="search()">
          <label class="block text-sm font-medium"
            >Médicament (nom, molécule ou code)
            <input
              type="search"
              class="field"
              placeholder="ex. Paracétamol, Amoxicilline…"
              [(ngModel)]="query"
              name="q"
              minlength="3"
              required
              autocomplete="off"
            />
          </label>
          <label class="block text-sm font-medium"
            >Ville
            <input
              class="field"
              placeholder="ex. Niamey"
              [(ngModel)]="city"
              name="city"
              autocomplete="address-level2"
            />
          </label>
          <button
            type="submit"
            class="w-full rounded-lg bg-brand py-3 text-lg font-semibold text-on-brand"
            [disabled]="busy() || query.trim().length < 3"
          >
            {{ busy() ? 'Recherche…' : 'Chercher' }}
          </button>
        </form>

        @if (error(); as text) {
          <p class="rounded bg-red-50 px-3 py-2 text-red-800" role="alert">{{ text }}</p>
        }

        @if (searched()) {
          <section class="space-y-3" aria-live="polite">
            @for (item of results(); track item.product.id) {
              <article class="rounded-xl border bg-white p-4 shadow-sm">
                <h2 class="font-semibold">{{ item.product.designation }}</h2>
                @if (item.product.dci) {
                  <p class="text-sm text-slate-500">{{ item.product.dci }}</p>
                }
                <ul class="mt-2 divide-y">
                  @for (p of item.pharmacies; track p.tenantId) {
                    <li class="flex items-center justify-between gap-2 py-2">
                      <div>
                        <p class="font-medium">{{ p.pharmacyName }}</p>
                        <p class="text-sm text-slate-600">{{ p.address }} {{ p.city }}</p>
                      </div>
                      <div class="flex items-center gap-2">
                        <span
                          class="rounded-full px-2 py-0.5 text-sm"
                          [class.bg-green-100]="p.level === 'IN_STOCK'"
                          [class.text-green-800]="p.level === 'IN_STOCK'"
                          [class.bg-amber-100]="p.level === 'LOW'"
                          [class.text-amber-800]="p.level === 'LOW'"
                        >
                          {{ p.level === 'IN_STOCK' ? 'Disponible' : 'Dernières unités' }}
                        </span>
                        @if (p.phone) {
                          <a
                            class="rounded border px-3 py-2 text-sm"
                            [href]="'tel:' + p.phone"
                            aria-label="Appeler"
                            >Appeler</a
                          >
                        }
                        @if (p.whatsapp) {
                          <a
                            class="rounded border px-3 py-2 text-sm"
                            [href]="whatsappLink(p.whatsapp, item.product.designation)"
                            target="_blank"
                            rel="noopener"
                            >WhatsApp</a
                          >
                        }
                      </div>
                    </li>
                  }
                </ul>
              </article>
            } @empty {
              <p class="rounded-xl border bg-white p-4 text-slate-600">
                Aucune pharmacie n'a déclaré ce produit disponible
                {{ city.trim() ? 'à ' + city.trim() : '' }}. Essayez un autre nom ou la molécule.
              </p>
            }
          </section>
        }

        <section class="rounded-xl border-2 border-brand bg-white p-4 shadow-sm">
          <div class="flex items-center justify-between gap-2">
            <h2 class="text-lg font-semibold">Pharmacies de garde maintenant</h2>
            <button type="button" class="rounded border px-3 py-2 text-sm" (click)="loadGuards()">
              Actualiser
            </button>
          </div>
          <ul class="mt-2 divide-y">
            @for (g of guards(); track g.slotId) {
              <li class="py-2">
                <p class="text-lg font-medium">{{ g.pharmacyName }}</p>
                <p class="text-sm text-slate-600">
                  {{ g.address }} {{ g.city }} · {{ typeLabel(g.type) }} jusqu'à
                  {{ g.endsAt | date: 'EEE HH:mm' }}
                </p>
                <div class="mt-1 flex gap-2">
                  @if (g.phone) {
                    <a class="rounded border px-3 py-2 text-sm" [href]="'tel:' + g.phone">{{
                      g.phone
                    }}</a>
                  }
                  @if (g.whatsapp) {
                    <a
                      class="rounded border px-3 py-2 text-sm"
                      [href]="whatsappLink(g.whatsapp, null)"
                      target="_blank"
                      rel="noopener"
                      >WhatsApp</a
                    >
                  }
                  @if (g.latitude !== null && g.longitude !== null) {
                    <a
                      class="rounded border px-3 py-2 text-sm"
                      [href]="mapLink(g.latitude, g.longitude)"
                      target="_blank"
                      rel="noopener"
                      >Itinéraire</a
                    >
                  }
                </div>
              </li>
            } @empty {
              <li class="py-2 text-slate-600">
                Aucune garde publiée pour le moment{{ city.trim() ? ' à ' + city.trim() : '' }}.
              </li>
            }
          </ul>
          <div class="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              class="rounded bg-brand px-3 py-2 text-on-brand"
              (click)="share()"
              [disabled]="busy()"
            >
              Partager la liste du jour
            </button>
            @if (shareText(); as text) {
              <a
                class="rounded border px-3 py-2"
                [href]="'https://wa.me/?text=' + encode(text)"
                target="_blank"
                rel="noopener"
                >Envoyer sur WhatsApp</a
              >
              <button type="button" class="rounded border px-3 py-2" (click)="copy(text)">
                {{ copied() ? 'Copié' : 'Copier' }}
              </button>
            }
          </div>
          @if (shareText(); as text) {
            <pre class="mt-2 whitespace-pre-wrap rounded bg-slate-100 p-3 text-sm">{{ text }}</pre>
          }
        </section>

        <section class="rounded-xl border bg-white p-4 shadow-sm">
          <h2 class="text-lg font-semibold">
            Pharmacies {{ city.trim() ? 'à ' + city.trim() : '' }}
          </h2>
          <button
            type="button"
            class="mt-2 rounded border px-3 py-2 text-sm"
            (click)="loadPharmacies()"
          >
            Afficher la liste
          </button>
          <ul class="mt-2 divide-y">
            @for (p of pharmacies(); track p.tenantId) {
              <li class="py-2">
                <p class="font-medium">
                  {{ p.pharmacyName }}
                  @if (p.sharesAvailability) {
                    <small class="ml-1 rounded bg-slate-100 px-1 text-xs"
                      >disponibilités publiées</small
                    >
                  }
                </p>
                <p class="text-sm text-slate-600">
                  {{ p.address }} {{ p.city }} · {{ p.openingHours }}
                </p>
                @if (p.phone) {
                  <a class="text-sm underline" [href]="'tel:' + p.phone">{{ p.phone }}</a>
                }
              </li>
            }
          </ul>
        </section>
      </div>
    </main>
  `,
  styles: `
    .field {
      display: block;
      width: 100%;
      margin-top: 0.25rem;
      border: 1px solid #cbd5e1;
      border-radius: 0.5rem;
      padding: 0.75rem;
      font-size: 1.125rem;
    }
  `,
})
export class PublicDirectoryPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(NodeInfoService).networkApiUrl()}/public/directory`;

  protected query = '';
  protected city = '';
  protected readonly busy = signal(false);
  protected readonly searched = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly results = signal<PublicProductAvailability[]>([]);
  protected readonly guards = signal<PublicGuardSlot[]>([]);
  protected readonly pharmacies = signal<PublicPharmacy[]>([]);
  protected readonly shareText = signal<string | null>(null);
  protected readonly copied = signal(false);

  constructor() {
    void this.loadGuards();
  }

  protected async search(): Promise<void> {
    if (this.query.trim().length < 3) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      this.results.set(
        await firstValueFrom(
          this.http.get<PublicProductAvailability[]>(`${this.api}/availability`, {
            params: this.params({ q: this.query.trim() }),
          }),
        ),
      );
      this.searched.set(true);
    } catch (error) {
      this.error.set(this.message(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async loadGuards(): Promise<void> {
    try {
      this.guards.set(
        await firstValueFrom(
          this.http.get<PublicGuardSlot[]>(`${this.api}/guards`, { params: this.params({}) }),
        ),
      );
    } catch (error) {
      this.error.set(this.message(error));
    }
  }

  protected async loadPharmacies(): Promise<void> {
    try {
      this.pharmacies.set(
        await firstValueFrom(
          this.http.get<PublicPharmacy[]>(`${this.api}/pharmacies`, { params: this.params({}) }),
        ),
      );
    } catch (error) {
      this.error.set(this.message(error));
    }
  }

  protected async share(): Promise<void> {
    this.busy.set(true);
    try {
      const reply = await firstValueFrom(
        this.http.get<{ text: string }>(`${this.api}/guards/share-text`, {
          params: this.params({}),
        }),
      );
      this.shareText.set(reply.text);
      this.copied.set(false);
    } catch (error) {
      this.error.set(this.message(error));
    } finally {
      this.busy.set(false);
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

  protected whatsappLink(number: string, product: string | null): string {
    const digits = number.replace(/[^0-9]/g, '');
    const text = product ? `Bonjour, avez-vous ${product} disponible ?` : 'Bonjour';
    return `https://wa.me/${digits}?text=${encodeURIComponent(text)}`;
  }

  protected mapLink(latitude: number, longitude: number): string {
    return `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=17/${latitude}/${longitude}`;
  }

  protected typeLabel(type: PublicGuardSlot['type']): string {
    return TYPE_LABEL[type];
  }

  private params(extra: Record<string, string>): Record<string, string> {
    const city = this.city.trim();
    return city ? { ...extra, city } : extra;
  }

  private message(error: unknown): string {
    const status = (error as { status?: number }).status;
    if (status === 429) {
      return 'Trop de recherches depuis cet appareil, réessayez dans une minute.';
    }
    return (
      (error as { error?: { detail?: string } }).error?.detail ??
      'Service momentanément indisponible, réessayez.'
    );
  }
}
