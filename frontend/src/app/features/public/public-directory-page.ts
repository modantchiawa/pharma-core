import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PublicGuardSlot, PublicPharmacy, PublicProductAvailability } from '../../core/api/models';
import { NodeInfoService } from '../../core/api/node-info.service';
import { Icon } from '../../shared/ui/icon';

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
  imports: [FormsModule, DatePipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="min-h-screen bg-[var(--app-bg)] text-ink">
      <header class="hero text-on-brand">
        <div class="relative mx-auto max-w-5xl px-4 pt-5 pb-24 sm:px-6 sm:pt-8 sm:pb-32">
          <p class="flex items-center gap-2.5 text-sm font-semibold">
            <span class="flex h-8 w-8 items-center justify-center rounded-lg bg-on-brand/15">
              <app-icon name="pill" [size]="18" />
            </span>
            Annuaire des pharmacies
          </p>
          <h1
            class="mt-8 max-w-2xl text-[1.75rem] leading-tight font-bold tracking-tight text-on-brand sm:mt-12 sm:text-4xl"
          >
            Trouver un médicament, une pharmacie de garde
          </h1>
          <p class="mt-3 max-w-xl text-[0.95rem] opacity-90 sm:text-base">
            Disponibilités déclarées par les pharmacies. Appelez avant de vous déplacer.
          </p>
        </div>
      </header>

      <div class="relative mx-auto -mt-16 max-w-5xl space-y-10 px-4 pb-12 sm:-mt-20 sm:px-6">
        <form class="card p-4 shadow-[var(--shadow-lg)] sm:p-5" (ngSubmit)="search()">
          <div class="grid gap-3 md:grid-cols-[minmax(0,1fr)_15rem_auto] md:items-end">
            <label class="block"
              ><span class="label">Médicament (nom, molécule ou code)</span>
              <span class="input-with-icon block">
                <app-icon name="search" [size]="18" />
                <input
                  type="search"
                  class="input h-12 text-base"
                  placeholder="ex. Paracétamol, Amoxicilline…"
                  [(ngModel)]="query"
                  name="q"
                  minlength="3"
                  required
                  autocomplete="off"
                />
              </span>
            </label>
            <label class="block"
              ><span class="label">Ville</span>
              <span class="input-with-icon block">
                <app-icon name="mapPin" [size]="18" />
                <input
                  class="input h-12 text-base"
                  placeholder="ex. Niamey"
                  [(ngModel)]="city"
                  name="city"
                  autocomplete="address-level2"
                />
              </span>
            </label>
            <button
              type="submit"
              class="btn btn-primary btn-lg h-12 w-full md:w-auto"
              [disabled]="busy() || query.trim().length < 3"
            >
              <app-icon name="search" [size]="18" />
              {{ busy() ? 'Recherche…' : 'Chercher' }}
            </button>
          </div>
        </form>

        @if (error(); as text) {
          <p class="alert alert-danger" role="alert">
            <app-icon name="error" [size]="18" />
            <span>{{ text }}</span>
          </p>
        }

        @if (searched()) {
          <section class="space-y-4" aria-live="polite">
            <h2 class="section-title">
              <app-icon name="search" [size]="18" class="text-muted" />
              Résultats
              <span class="badge">{{ results().length }}</span>
            </h2>
            @for (item of results(); track item.product.id) {
              <article class="card overflow-hidden">
                <div class="flex items-start gap-3 border-b border-line px-4 py-3 sm:px-5">
                  <span class="tile"><app-icon name="pill" [size]="18" /></span>
                  <div class="min-w-0">
                    <h3 class="font-semibold">{{ item.product.designation }}</h3>
                    @if (item.product.dci) {
                      <p class="text-sm text-muted">{{ item.product.dci }}</p>
                    }
                  </div>
                </div>
                <ul class="divide-y divide-[var(--border)]">
                  @for (p of item.pharmacies; track p.tenantId) {
                    <li
                      class="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-5"
                    >
                      <div class="min-w-0">
                        <p class="flex flex-wrap items-center gap-2 font-medium">
                          {{ p.pharmacyName }}
                          <span
                            class="badge"
                            [class.badge-success]="p.level === 'IN_STOCK'"
                            [class.badge-warning]="p.level === 'LOW'"
                          >
                            <span class="dot"></span>
                            {{ p.level === 'IN_STOCK' ? 'Disponible' : 'Dernières unités' }}
                          </span>
                        </p>
                        <p class="mt-0.5 flex items-start gap-1.5 text-sm text-muted">
                          <app-icon name="mapPin" [size]="14" class="mt-[3px]" />
                          {{ p.address }} {{ p.city }}
                        </p>
                      </div>
                      <div class="actions sm:shrink-0">
                        @if (p.phone) {
                          <a
                            class="btn btn-secondary"
                            [href]="'tel:' + p.phone"
                            aria-label="Appeler"
                          >
                            <app-icon name="phone" [size]="16" />
                            Appeler
                          </a>
                        }
                        @if (p.whatsapp) {
                          <a
                            class="btn btn-secondary"
                            [href]="whatsappLink(p.whatsapp, item.product.designation)"
                            target="_blank"
                            rel="noopener"
                          >
                            <app-icon name="chat" [size]="16" />
                            WhatsApp
                          </a>
                        }
                      </div>
                    </li>
                  }
                </ul>
              </article>
            } @empty {
              <div class="card empty-state">
                <app-icon name="search" [size]="22" />
                <p class="max-w-md">
                  Aucune pharmacie n'a déclaré ce produit disponible
                  {{ city.trim() ? 'à ' + city.trim() : '' }}. Essayez un autre nom ou la molécule.
                </p>
              </div>
            }
          </section>
        }

        <section class="space-y-4">
          <div class="flex items-center justify-between gap-3">
            <h2 class="section-title">
              <span class="tile tile-night"><app-icon name="moon" [size]="18" /></span>
              Pharmacies de garde maintenant
            </h2>
            <button type="button" class="btn btn-ghost btn-sm shrink-0" (click)="loadGuards()">
              <app-icon name="refresh" [size]="16" />
              Actualiser
            </button>
          </div>
          <ul class="grid gap-4 sm:grid-cols-2">
            @for (g of guards(); track g.slotId) {
              <li class="card guard flex flex-col p-4 sm:p-5">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="badge badge-success"
                    ><span class="dot animate-pulse"></span>De garde</span
                  >
                  <span class="badge">{{ typeLabel(g.type) }}</span>
                </div>
                <p class="mt-3 text-lg font-semibold leading-snug">{{ g.pharmacyName }}</p>
                <p class="mt-1 flex items-start gap-1.5 text-sm text-muted">
                  <app-icon name="mapPin" [size]="14" class="mt-[3px]" />
                  {{ g.address }} {{ g.city }}
                </p>
                <p class="mt-1 flex items-center gap-1.5 text-sm text-muted">
                  <app-icon name="clock" [size]="14" />
                  {{ typeLabel(g.type) }} jusqu'à {{ g.endsAt | date: 'EEE HH:mm' }}
                </p>
                <div class="actions mt-4 pt-1">
                  @if (g.phone) {
                    <a class="btn btn-primary" [href]="'tel:' + g.phone">
                      <app-icon name="phone" [size]="16" />
                      {{ g.phone }}
                    </a>
                  }
                  @if (g.whatsapp) {
                    <a
                      class="btn btn-secondary"
                      [href]="whatsappLink(g.whatsapp, null)"
                      target="_blank"
                      rel="noopener"
                    >
                      <app-icon name="chat" [size]="16" />
                      WhatsApp
                    </a>
                  }
                  @if (g.latitude !== null && g.longitude !== null) {
                    <a
                      class="btn btn-secondary"
                      [href]="mapLink(g.latitude, g.longitude)"
                      target="_blank"
                      rel="noopener"
                    >
                      <app-icon name="navigation" [size]="16" />
                      Itinéraire
                    </a>
                  }
                </div>
              </li>
            } @empty {
              <li class="card empty-state sm:col-span-2">
                <app-icon name="moon" [size]="22" />
                <p>
                  Aucune garde publiée pour le moment{{ city.trim() ? ' à ' + city.trim() : '' }}.
                </p>
              </li>
            }
          </ul>

          <div class="card p-4 sm:p-5">
            <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p class="font-medium">Liste des gardes du jour</p>
                <p class="text-sm text-muted">À transmettre à vos proches par message.</p>
              </div>
              <div class="actions sm:shrink-0">
                <button
                  type="button"
                  class="btn btn-secondary"
                  (click)="share()"
                  [disabled]="busy()"
                >
                  <app-icon name="share" [size]="16" />
                  Partager la liste du jour
                </button>
                @if (shareText(); as text) {
                  <a
                    class="btn btn-secondary"
                    [href]="'https://wa.me/?text=' + encode(text)"
                    target="_blank"
                    rel="noopener"
                  >
                    <app-icon name="chat" [size]="16" />
                    Envoyer sur WhatsApp
                  </a>
                  <button type="button" class="btn btn-ghost" (click)="copy(text)">
                    <app-icon [name]="copied() ? 'check' : 'copy'" [size]="16" />
                    {{ copied() ? 'Copié' : 'Copier' }}
                  </button>
                }
              </div>
            </div>
            @if (shareText(); as text) {
              <pre
                class="mt-4 rounded-[var(--radius)] border border-line bg-surface-2 p-3 font-sans text-sm whitespace-pre-wrap"
                >{{ text }}</pre>
            }
          </div>
        </section>

        <section class="space-y-4">
          <div class="flex items-center justify-between gap-3">
            <h2 class="section-title">
              <span class="tile"><app-icon name="store" [size]="18" /></span>
              Pharmacies {{ city.trim() ? 'à ' + city.trim() : '' }}
            </h2>
            <button
              type="button"
              class="btn btn-secondary btn-sm shrink-0"
              (click)="loadPharmacies()"
            >
              Afficher la liste
            </button>
          </div>
          @if (pharmacies().length > 0) {
            <ul class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              @for (p of pharmacies(); track p.tenantId) {
                <li class="card flex flex-col p-4">
                  <p class="font-semibold leading-snug">{{ p.pharmacyName }}</p>
                  @if (p.sharesAvailability) {
                    <span class="badge badge-brand mt-2 self-start">
                      <app-icon name="checkCircle" [size]="12" />
                      disponibilités publiées
                    </span>
                  }
                  <p class="mt-2 flex items-start gap-1.5 text-sm text-muted">
                    <app-icon name="mapPin" [size]="14" class="mt-[3px]" />
                    {{ p.address }} {{ p.city }}
                  </p>
                  @if (p.openingHours) {
                    <p class="mt-1 flex items-start gap-1.5 text-sm text-muted">
                      <app-icon name="clock" [size]="14" class="mt-[3px]" />
                      {{ p.openingHours }}
                    </p>
                  }
                  @if (p.phone) {
                    <a class="btn btn-secondary btn-sm mt-4 self-start" [href]="'tel:' + p.phone">
                      <app-icon name="phone" [size]="14" />
                      {{ p.phone }}
                    </a>
                  }
                </li>
              }
            </ul>
          } @else {
            <div class="card empty-state">
              <app-icon name="store" [size]="22" />
              <p>Toutes les pharmacies de l'annuaire, avec leurs horaires et leur téléphone.</p>
            </div>
          }
        </section>

        <footer class="flex items-center justify-center gap-2 pt-2 text-xs text-subtle">
          <app-icon name="info" [size]="14" />
          Informations publiées par les pharmacies elles-mêmes.
        </footer>
      </div>
    </main>
  `,
  styles: `
    .hero {
      position: relative;
      overflow: hidden;
      background:
        radial-gradient(60rem 30rem at 85% -10%, rgb(255 255 255 / 0.14), transparent 60%),
        radial-gradient(40rem 20rem at 0% 120%, rgb(0 0 0 / 0.18), transparent 60%),
        linear-gradient(135deg, var(--brand-primary), var(--brand-secondary));
    }
    .section-title {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      font-size: 1.125rem;
      font-weight: 650;
    }
    .tile {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.25rem;
      height: 2.25rem;
      flex-shrink: 0;
      border-radius: 0.65rem;
      background: var(--brand-soft);
      color: var(--brand-text);
    }
    .tile-night {
      background: var(--info-soft);
      color: var(--info);
    }
    .guard {
      border-top: 3px solid var(--success);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    .actions > * {
      flex: 1 1 auto;
    }
    @media (min-width: 640px) {
      .actions > * {
        flex: 0 0 auto;
      }
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
