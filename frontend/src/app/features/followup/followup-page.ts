import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  Customer,
  CustomerMessage,
  FollowUpReport,
  FollowUpSettings,
  FollowUpSettingsRequest,
  MESSAGE_KIND_LABELS,
  MessageKind,
  MessageQueue,
  ProductWish,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';
import { SkeletonRows } from '../../shared/ui/skeleton-rows';

type Tab = 'queue' | 'history' | 'wishes' | 'settings';

const TEMPLATE_KINDS: MessageKind[] = [
  'TREATMENT_END',
  'RENEWAL_DUE',
  'PRODUCT_AVAILABLE',
  'CREDIT_REMINDER',
];

/**
 * Patient follow-up: the messages the pharmacy owes its patients today (treatment ending,
 * renewal due, product back in stock, unpaid account), each sent by WhatsApp in one tap from the
 * pre-filled link, the products customers are waiting for, and the settings (templates, SMS
 * gateway, evening digest to the owner).
 */
@Component({
  selector: 'app-followup-page',
  imports: [DatePipe, FormsModule, HasPermissionDirective, Icon, EmptyState, SkeletonRows],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Suivi patients</h1>
          <p class="page-subtitle">
            Vos patients reçoivent un message au bon moment : fin de traitement, renouvellement,
            produit arrivé, créance. WhatsApp en un geste, SMS automatique si une passerelle est
            réglée.
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <button type="button" class="btn btn-secondary" (click)="generate()" [disabled]="busy()">
            <app-icon name="refresh" [size]="16" />
            Préparer les messages du jour
          </button>
          <button type="button" class="btn btn-primary" (click)="composing.set(true)">
            <app-icon name="messageCircle" [size]="16" />
            Écrire à un client
          </button>
        </div>
      </header>

      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div class="segmented" role="tablist" aria-label="Suivi">
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="tab() === 'queue'"
            (click)="show('queue')"
          >
            À envoyer
            @if (queue()?.pending; as pending) {
              <span class="badge badge-brand ml-1">{{ pending }}</span>
            }
          </button>
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="tab() === 'history'"
            (click)="show('history')"
          >
            Historique
          </button>
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="tab() === 'wishes'"
            (click)="show('wishes')"
          >
            Produits attendus
            @if (waitingCount() > 0) {
              <span class="badge ml-1">{{ waitingCount() }}</span>
            }
          </button>
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="tab() === 'settings'"
            (click)="show('settings')"
            *appHasPermission="'FOLLOWUP_SETTINGS'"
          >
            Réglages
          </button>
        </div>
        @if (queue(); as q) {
          <p class="text-sm text-muted">{{ q.sentToday }} envoyé(s) aujourd'hui</p>
        }
      </div>

      @if (tab() === 'queue' || tab() === 'history') {
        <section class="card">
          @if (!loaded()) {
            <app-skeleton-rows [rows]="5" [figures]="2" />
          } @else if (messages().length > 0) {
            <ul class="divide-y">
              @for (m of messages(); track m.id) {
                <li class="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-start">
                  <div class="min-w-0 flex-1">
                    <p class="flex flex-wrap items-center gap-2">
                      <span class="font-semibold">{{ m.customerName }}</span>
                      <span class="badge">{{ kindLabel(m.kind) }}</span>
                      <span
                        class="badge"
                        [class.badge-success]="m.status === 'SENT'"
                        [class.badge-danger]="m.status === 'FAILED'"
                      >
                        {{ statusLabel(m.status) }} · {{ m.channel === 'SMS' ? 'SMS' : 'WhatsApp' }}
                      </span>
                      <span class="text-xs text-subtle">+{{ m.phone }}</span>
                    </p>
                    <p class="mt-1 text-sm whitespace-pre-line">{{ m.body }}</p>
                    <p class="mt-1 text-xs text-subtle">
                      préparé le {{ m.createdAt | date: 'dd/MM HH:mm' }}
                      @if (m.sentAt) {
                        · envoyé le {{ m.sentAt | date: 'dd/MM HH:mm' }}
                        @if (m.sentBy) {
                          par {{ m.sentBy }}
                        }
                      }
                      @if (m.lastError) {
                        · <span class="text-danger">{{ m.lastError }}</span>
                      }
                    </p>
                  </div>
                  @if (m.status === 'PENDING' || m.status === 'FAILED') {
                    <div class="flex shrink-0 gap-2">
                      @if (m.whatsappLink || m.status === 'FAILED') {
                        <a
                          class="btn btn-primary btn-sm"
                          [href]="m.whatsappLink ?? whatsappFallback(m)"
                          target="_blank"
                          rel="noopener"
                          (click)="opened(m)"
                        >
                          <app-icon name="messageCircle" [size]="14" />
                          Envoyer sur WhatsApp
                        </a>
                      } @else {
                        <span class="badge">SMS en attente d'envoi</span>
                      }
                      <button type="button" class="btn btn-ghost btn-sm" (click)="markSent(m)">
                        <app-icon name="check" [size]="14" />
                        Envoyé
                      </button>
                      <button
                        type="button"
                        class="btn btn-ghost btn-sm text-muted"
                        (click)="cancel(m)"
                      >
                        <app-icon name="x" [size]="14" />
                      </button>
                    </div>
                  }
                </li>
              }
            </ul>
          } @else {
            <app-empty-state illustration="people">
              <p>
                {{
                  tab() === 'queue'
                    ? 'Aucun message à envoyer : les rappels sont préparés chaque heure.'
                    : 'Aucun message envoyé pour le moment.'
                }}
              </p>
            </app-empty-state>
          }
        </section>
      }

      @if (tab() === 'wishes') {
        <section class="card">
          @if (wishes().length > 0) {
            <div class="overflow-x-auto">
              <table class="data-table whitespace-nowrap">
                <thead>
                  <tr>
                    <th>Client</th>
                    <th>Produit</th>
                    <th class="text-right">Qté</th>
                    <th>Depuis</th>
                    <th>État</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  @for (w of wishes(); track w.id) {
                    <tr>
                      <td>
                        <p class="font-medium">{{ w.customerName }}</p>
                        <p class="text-xs text-subtle">{{ w.customerPhone }}</p>
                      </td>
                      <td>{{ w.designation }}</td>
                      <td class="num">{{ w.quantity }}</td>
                      <td class="text-muted">{{ w.requestedAt | date: 'dd/MM/yyyy' }}</td>
                      <td>
                        <span class="badge" [class.badge-success]="w.status === 'NOTIFIED'">
                          {{
                            w.status === 'WAITING'
                              ? 'En attente'
                              : w.status === 'NOTIFIED'
                                ? 'Prévenu'
                                : 'Annulé'
                          }}
                        </span>
                      </td>
                      <td>
                        @if (w.status === 'WAITING') {
                          <button
                            type="button"
                            class="btn btn-ghost btn-sm"
                            (click)="cancelWish(w)"
                          >
                            Annuler
                          </button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <app-empty-state illustration="cart">
              <p>
                Aucun produit attendu. Au comptoir, sur un produit en rupture, choisissez « Prévenir
                à l'arrivée » pour un client qui a donné son accord.
              </p>
            </app-empty-state>
          }
        </section>
      }

      @if (tab() === 'settings' && settingsForm; as form) {
        <form class="grid gap-5 lg:grid-cols-2" (ngSubmit)="saveSettings()">
          <section class="card">
            <div class="card-header"><h3 class="card-title">Rappels automatiques</h3></div>
            <div class="card-body space-y-3 text-sm">
              <label class="flex items-center gap-2">
                <input type="checkbox" [(ngModel)]="form.enabled" name="enabled" />
                Suivi des patients activé (seuls les clients ayant donné leur accord sont contactés)
              </label>
              <label class="flex items-center gap-2">
                <input type="checkbox" [(ngModel)]="form.treatmentEndEnabled" name="treatment" />
                Fin de traitement (d'après la posologie saisie au comptoir, 2 jours avant la fin)
              </label>
              <label class="flex items-center gap-2">
                <input type="checkbox" [(ngModel)]="form.renewalEnabled" name="renewal" />
                Ordonnance renouvelable due
              </label>
              <label class="block pl-6">
                <span class="label">Prévenir (jours avant)</span>
                <input
                  type="number"
                  class="input w-28"
                  min="0"
                  max="30"
                  [(ngModel)]="form.renewalDaysAhead"
                  name="renewalDays"
                />
              </label>
              <label class="flex items-center gap-2">
                <input type="checkbox" [(ngModel)]="form.productWishEnabled" name="wish" />
                Produit attendu arrivé
              </label>
              <label class="flex items-center gap-2">
                <input type="checkbox" [(ngModel)]="form.creditReminderEnabled" name="credit" />
                Rappel de créance
              </label>
              <div class="grid grid-cols-2 gap-3 pl-6">
                <label class="block">
                  <span class="label">Après (jours sans mouvement)</span>
                  <input
                    type="number"
                    class="input"
                    min="1"
                    max="365"
                    [(ngModel)]="form.creditReminderAfterDays"
                    name="creditAfter"
                  />
                </label>
                <label class="block">
                  <span class="label">Au plus tous les (jours)</span>
                  <input
                    type="number"
                    class="input"
                    min="1"
                    max="365"
                    [(ngModel)]="form.creditReminderEveryDays"
                    name="creditEvery"
                  />
                </label>
              </div>
            </div>
          </section>

          <section class="card">
            <div class="card-header"><h3 class="card-title">Rapport du soir au titulaire</h3></div>
            <div class="card-body space-y-3 text-sm">
              <label class="flex items-center gap-2">
                <input type="checkbox" [(ngModel)]="form.digestEnabled" name="digest" />
                Chaque soir, les chiffres du jour dans les alertes (cloche, webhook) et par SMS
              </label>
              <div class="grid grid-cols-2 gap-3">
                <label class="block">
                  <span class="label">Heure</span>
                  <input
                    type="number"
                    class="input"
                    min="0"
                    max="23"
                    [(ngModel)]="form.digestHour"
                    name="digestHour"
                  />
                </label>
                <label class="block">
                  <span class="label">Téléphone du titulaire (SMS)</span>
                  <input
                    class="input"
                    [(ngModel)]="form.digestPhone"
                    name="digestPhone"
                    placeholder="+227 90 00 00 00"
                  />
                </label>
              </div>
            </div>
            <div class="card-header border-t">
              <h3 class="card-title">Passerelle SMS (facultative)</h3>
            </div>
            <div class="card-body space-y-3 text-sm">
              <p class="text-xs text-muted">
                Sans passerelle, tous les messages partent par WhatsApp depuis le poste. Avec une
                passerelle HTTPS (POST JSON <code>to</code>, <code>from</code>, <code>text</code>,
                jeton Bearer), les clients qui préfèrent le SMS le reçoivent automatiquement.
              </p>
              <label class="block">
                <span class="label">URL</span>
                <input
                  class="input"
                  [(ngModel)]="form.smsGatewayUrl"
                  name="smsUrl"
                  placeholder="https://…"
                />
              </label>
              <div class="grid grid-cols-2 gap-3">
                <label class="block">
                  <span class="label"
                    >Jeton {{ settings()?.smsTokenSet ? '(enregistré)' : '' }}</span
                  >
                  <input
                    class="input"
                    type="password"
                    autocomplete="off"
                    [(ngModel)]="smsToken"
                    name="smsToken"
                    [placeholder]="settings()?.smsTokenSet ? 'Laisser vide pour conserver' : ''"
                  />
                </label>
                <label class="block">
                  <span class="label">Expéditeur (11 car.)</span>
                  <input
                    class="input"
                    maxlength="11"
                    [(ngModel)]="form.smsSender"
                    name="smsSender"
                  />
                </label>
              </div>
            </div>
          </section>

          <section class="card lg:col-span-2">
            <div class="card-header">
              <h3 class="card-title">Modèles de messages</h3>
              <span class="text-xs text-subtle">
                Variables : {{ '{client}' }} {{ '{pharmacie}' }} {{ '{telephone}' }}
                {{ '{produit}' }} {{ '{montant}' }} {{ '{date}' }}
              </span>
            </div>
            <div class="card-body grid gap-4 md:grid-cols-2">
              @for (kind of templateKinds; track kind) {
                <label class="block">
                  <span class="label">{{ kindLabel(kind) }}</span>
                  <textarea
                    class="textarea"
                    rows="3"
                    maxlength="600"
                    [(ngModel)]="form.templates[kind]"
                    [name]="'template-' + kind"
                    [placeholder]="settings()?.defaultTemplates?.[kind] ?? ''"
                  ></textarea>
                </label>
              }
            </div>
            <div class="flex justify-end gap-2 border-t p-4">
              <button type="submit" class="btn btn-primary" [disabled]="busy()">
                <app-icon name="check" [size]="16" />
                Enregistrer les réglages
              </button>
            </div>
          </section>
        </form>
      }
    </div>

    @if (composing()) {
      <div
        class="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(2_6_12/0.5)] p-0 backdrop-blur-sm sm:items-center sm:p-4"
        role="dialog"
        aria-modal="true"
        aria-label="Écrire à un client"
      >
        <form
          class="card w-full max-w-md rounded-b-none shadow-[var(--shadow-lg)] animate-in sm:rounded-b-[var(--radius-lg)]"
          (ngSubmit)="compose()"
        >
          <div class="card-header"><h3 class="card-title">Écrire à un client</h3></div>
          <div class="card-body space-y-3">
            <div class="relative">
              @if (composeCustomer(); as c) {
                <div
                  class="flex items-center gap-2 rounded-[var(--radius)] border px-3 py-2 text-sm"
                >
                  <span class="flex-1 font-medium">{{ c.displayName }} · {{ c.phone }}</span>
                  <button
                    type="button"
                    class="btn btn-ghost btn-icon btn-sm"
                    (click)="composeCustomer.set(null)"
                    aria-label="Changer de client"
                  >
                    <app-icon name="x" [size]="14" />
                  </button>
                </div>
                @if (!c.messagingConsent) {
                  <p class="mt-1 text-xs text-danger">
                    Ce client n'a pas donné son accord : cochez-le dans sa fiche.
                  </p>
                }
              } @else {
                <input
                  type="search"
                  class="input"
                  placeholder="Client (nom, téléphone)"
                  [(ngModel)]="customerTerm"
                  name="customerTerm"
                  (ngModelChange)="searchCustomers($event)"
                />
                @if (customerResults().length > 0) {
                  <ul
                    class="results absolute z-20 mt-2 max-h-56 w-full overflow-auto p-1"
                    role="listbox"
                  >
                    @for (c of customerResults(); track c.id) {
                      <li
                        role="option"
                        class="cursor-pointer rounded-[var(--radius-sm)] px-3 py-2 text-sm"
                        (mousedown)="composeCustomer.set(c); customerResults.set([])"
                      >
                        {{ c.displayName }} <span class="text-muted">· {{ c.phone }}</span>
                      </li>
                    }
                  </ul>
                }
              }
            </div>
            <textarea
              class="textarea"
              rows="4"
              maxlength="600"
              [(ngModel)]="composeText"
              name="composeText"
              placeholder="Votre message"
            ></textarea>
          </div>
          <div class="flex justify-end gap-2 border-t p-4">
            <button type="button" class="btn btn-ghost" (click)="composing.set(false)">
              Annuler
            </button>
            <button
              type="submit"
              class="btn btn-primary"
              [disabled]="!composeCustomer() || !composeText.trim()"
            >
              <app-icon name="send" [size]="16" />
              Préparer le message
            </button>
          </div>
        </form>
      </div>
    }
  `,
})
export class FollowupPage {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(ToastService);
  private readonly permissions = inject(PermissionService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/followup`;
  private readonly customersApi = `${inject(APP_ENVIRONMENT).apiUrl}/v1/customers`;

  protected readonly tab = signal<Tab>('queue');
  protected readonly loaded = signal(false);
  protected readonly busy = signal(false);
  protected readonly queue = signal<MessageQueue | null>(null);
  protected readonly messages = signal<CustomerMessage[]>([]);
  protected readonly wishes = signal<ProductWish[]>([]);
  protected readonly waitingCount = computed(
    () => this.wishes().filter((w) => w.status === 'WAITING').length,
  );
  protected readonly settings = signal<FollowUpSettings | null>(null);
  protected settingsForm: FollowUpSettingsRequest | null = null;
  protected smsToken = '';
  protected readonly templateKinds = TEMPLATE_KINDS;

  protected readonly composing = signal(false);
  protected readonly composeCustomer = signal<Customer | null>(null);
  protected readonly customerResults = signal<Customer[]>([]);
  protected customerTerm = '';
  protected composeText = '';

  constructor() {
    void this.show('queue');
    void this.loadWishes();
  }

  protected async show(tab: Tab): Promise<void> {
    this.tab.set(tab);
    if (tab === 'queue' || tab === 'history') {
      await this.loadMessages(tab === 'queue' ? 'PENDING' : null);
    } else if (tab === 'wishes') {
      await this.loadWishes();
    } else if (tab === 'settings' && this.permissions.has('FOLLOWUP_SETTINGS')) {
      await this.loadSettings();
    }
  }

  private async loadMessages(status: string | null): Promise<void> {
    try {
      const queue = await firstValueFrom(
        this.http.get<MessageQueue>(`${this.api}/messages`, {
          params: status ? { status, limit: 200 } : { limit: 200 },
        }),
      );
      this.queue.set(queue);
      this.messages.set(
        status ? queue.messages : queue.messages.filter((m) => m.status !== 'PENDING'),
      );
    } catch {
      this.toasts.error('Messages indisponibles (serveur injoignable)');
    } finally {
      this.loaded.set(true);
    }
  }

  private async loadWishes(): Promise<void> {
    try {
      this.wishes.set(await firstValueFrom(this.http.get<ProductWish[]>(`${this.api}/wishes`)));
    } catch {
      this.wishes.set([]);
    }
  }

  private async loadSettings(): Promise<void> {
    try {
      const settings = await firstValueFrom(
        this.http.get<FollowUpSettings>(`${this.api}/settings`),
      );
      this.settings.set(settings);
      this.settingsForm = {
        ...settings.values,
        templates: { ...settings.values.templates },
        smsGatewayToken: null,
      };
      this.smsToken = '';
    } catch {
      this.toasts.error('Réglages indisponibles');
    }
  }

  protected kindLabel(kind: MessageKind): string {
    return MESSAGE_KIND_LABELS[kind];
  }

  protected statusLabel(status: CustomerMessage['status']): string {
    return { PENDING: 'À envoyer', SENT: 'Envoyé', FAILED: 'Échec', CANCELLED: 'Annulé' }[status];
  }

  /** A failed SMS can still go out by WhatsApp from the team's phone. */
  protected whatsappFallback(message: CustomerMessage): string {
    return `https://wa.me/${message.phone}?text=${encodeURIComponent(message.body)}`;
  }

  /** The link opened WhatsApp: the message is counted sent once the team confirms. */
  protected opened(message: CustomerMessage): void {
    this.toasts.info(
      `WhatsApp ouvert pour ${message.customerName} : confirmez « Envoyé » après l'envoi`,
    );
  }

  protected async markSent(message: CustomerMessage): Promise<void> {
    await this.act(
      `${this.api}/messages/${message.id}/sent`,
      `Message à ${message.customerName} envoyé`,
    );
  }

  protected async cancel(message: CustomerMessage): Promise<void> {
    await this.act(`${this.api}/messages/${message.id}/cancel`, 'Message annulé');
  }

  private async act(url: string, success: string): Promise<void> {
    try {
      await firstValueFrom(this.http.post(url, {}));
      this.toasts.success(success);
      await this.loadMessages(this.tab() === 'queue' ? 'PENDING' : null);
    } catch (error) {
      this.toasts.error(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Action refusée',
      );
    }
  }

  protected async generate(): Promise<void> {
    this.busy.set(true);
    try {
      const report = await firstValueFrom(
        this.http.post<FollowUpReport>(`${this.api}/messages/generate`, {}),
      );
      this.toasts.success(
        report.total > 0
          ? `${report.total} message(s) préparé(s)`
          : 'Rien de nouveau : tous les rappels du jour sont déjà prêts',
      );
      await this.show('queue');
    } catch {
      this.toasts.error('Préparation impossible');
    } finally {
      this.busy.set(false);
    }
  }

  protected async cancelWish(wish: ProductWish): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`${this.api}/wishes/${wish.id}/cancel`, {}));
      await this.loadWishes();
    } catch {
      this.toasts.error('Annulation refusée');
    }
  }

  protected async saveSettings(): Promise<void> {
    if (!this.settingsForm) {
      return;
    }
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(
        this.http.put<FollowUpSettings>(`${this.api}/settings`, {
          ...this.settingsForm,
          smsGatewayToken: this.smsToken === '' ? null : this.smsToken,
        }),
      );
      this.settings.set(saved);
      this.settingsForm = {
        ...saved.values,
        templates: { ...saved.values.templates },
        smsGatewayToken: null,
      };
      this.smsToken = '';
      this.toasts.success('Réglages du suivi enregistrés');
    } catch (error) {
      this.toasts.error(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Réglages refusés',
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected async searchCustomers(term: string): Promise<void> {
    if (term.trim().length < 2) {
      this.customerResults.set([]);
      return;
    }
    try {
      this.customerResults.set(
        await firstValueFrom(
          this.http.get<Customer[]>(this.customersApi, { params: { q: term.trim(), limit: 8 } }),
        ),
      );
    } catch {
      this.customerResults.set([]);
    }
  }

  protected async compose(): Promise<void> {
    const customer = this.composeCustomer();
    if (!customer || !this.composeText.trim()) {
      return;
    }
    try {
      const message = await firstValueFrom(
        this.http.post<CustomerMessage>(`${this.api}/messages`, {
          customerId: customer.id,
          text: this.composeText.trim(),
        }),
      );
      this.composing.set(false);
      this.composeText = '';
      this.composeCustomer.set(null);
      this.toasts.success(`Message pour ${message.customerName} prêt à envoyer`);
      await this.show('queue');
    } catch (error) {
      this.toasts.error(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Message refusé',
      );
    }
  }
}
