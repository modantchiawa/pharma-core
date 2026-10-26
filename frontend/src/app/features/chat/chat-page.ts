import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { NodeInfoService } from '../../core/api/node-info.service';
import { StompService } from '../../core/realtime/stomp.service';
import { Icon } from '../../shared/ui/icon';

interface Conversation {
  id: string;
  networkId: string;
  kind: 'NETWORK_CHANNEL' | 'DIRECT';
  title: string;
}

type MessageKind = 'TEXT' | 'STOCK_SHORTAGE' | 'HEALTH_ALERT' | 'TRANSFER';

const KIND_LABEL: Record<MessageKind, string> = {
  TEXT: 'Message',
  STOCK_SHORTAGE: 'Rupture',
  HEALTH_ALERT: 'Alerte sanitaire',
  TRANSFER: 'Transfert',
};

interface ChatMessage {
  id: string;
  conversationId: string;
  senderTenantId: string;
  senderDisplayName: string;
  senderPharmacyName: string;
  kind: MessageKind;
  body: string;
  sentAt: string;
}

/** Real-time messaging between pharmacists of the network (STOMP over WebSocket). */
@Component({
  selector: 'app-chat-page',
  imports: [FormsModule, DatePipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block md:h-full' },
  template: `
    <div class="page animate-in flex flex-col md:h-full">
      <header class="page-header">
        <div>
          <h1 class="page-title">Messagerie</h1>
          <p class="page-subtitle">
            Échanges en temps réel entre pharmaciens du réseau : ruptures, alertes, entraide
          </p>
        </div>
      </header>

      <div
        class="card grid grid-rows-[auto_minmax(28rem,1fr)] overflow-hidden md:min-h-0 md:flex-1 md:grid-cols-[300px_minmax(0,1fr)] md:grid-rows-1"
      >
        <aside class="flex min-h-0 flex-col border-b border-line md:border-r md:border-b-0">
          <div class="flex items-center justify-between gap-2 border-b border-line px-4 py-3">
            <h2 class="card-title">Conversations</h2>
            <span
              class="badge"
              [class.badge-success]="stomp.connected()"
              [class.badge-warning]="!stomp.connected()"
            >
              <span class="dot"></span>{{ stomp.connected() ? 'Connecté' : 'Connexion…' }}
            </span>
          </div>
          <ul class="max-h-60 flex-1 space-y-0.5 overflow-y-auto p-2 md:max-h-none">
            @for (c of conversations(); track c.id) {
              <li>
                <button
                  type="button"
                  class="conversation flex w-full items-center gap-3 rounded-[var(--radius-sm)] px-3 py-2.5 text-left"
                  [class.active]="c.id === active()?.id"
                  [attr.aria-current]="c.id === active()?.id ? 'true' : null"
                  (click)="open(c)"
                >
                  <span
                    class="conversation-icon flex h-9 w-9 shrink-0 items-center justify-center rounded-[var(--radius-sm)]"
                  >
                    <app-icon [name]="c.kind === 'DIRECT' ? 'user' : 'hash'" [size]="16" />
                  </span>
                  <span class="min-w-0 flex-1">
                    <span class="block truncate text-sm font-medium">{{ c.title }}</span>
                    <span class="block text-xs text-subtle">
                      {{ c.kind === 'DIRECT' ? 'Message direct' : 'Canal du réseau' }}
                    </span>
                  </span>
                </button>
              </li>
            } @empty {
              <li class="empty-state py-8">
                <app-icon name="chat" [size]="22" />
                <p>Aucune conversation</p>
              </li>
            }
          </ul>
        </aside>

        <section class="flex min-h-0 flex-col">
          @if (active(); as conversation) {
            <header class="flex items-center gap-3 border-b border-line px-5 py-3">
              <span
                class="flex h-9 w-9 shrink-0 items-center justify-center rounded-[var(--radius-sm)] bg-brand text-on-brand"
              >
                <app-icon [name]="conversation.kind === 'DIRECT' ? 'user' : 'hash'" [size]="16" />
              </span>
              <div class="min-w-0">
                <p class="truncate font-semibold">{{ conversation.title }}</p>
                <p class="text-xs text-subtle">
                  {{ conversation.kind === 'DIRECT' ? 'Message direct' : 'Canal du réseau' }}
                </p>
              </div>
            </header>
            <div
              class="flex min-h-0 flex-1 flex-col-reverse overflow-y-auto bg-surface-2 px-4 py-5 sm:px-6"
            >
              <ol class="space-y-4">
                @for (m of messages(); track m.id) {
                  <li
                    class="flex items-end gap-2.5"
                    [class.flex-row-reverse]="m.senderTenantId === myTenant()"
                  >
                    <span
                      class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[0.7rem] font-semibold"
                      [class]="
                        m.senderTenantId === myTenant()
                          ? 'bg-brand text-on-brand'
                          : 'bg-surface-3 text-muted'
                      "
                      [attr.title]="m.senderPharmacyName"
                      >{{ initials(m.senderDisplayName) }}</span
                    >
                    <div
                      class="flex max-w-[78%] flex-col sm:max-w-[65%]"
                      [class.items-end]="m.senderTenantId === myTenant()"
                    >
                      <p class="mb-1 px-1 text-xs text-subtle">
                        <span class="font-medium text-muted">{{ m.senderDisplayName }}</span>
                        · {{ m.senderPharmacyName }} · {{ m.sentAt | date: 'dd/MM HH:mm' }}
                      </p>
                      <div
                        class="bubble"
                        [class.mine]="m.senderTenantId === myTenant()"
                        [class.health-alert]="m.kind === 'HEALTH_ALERT'"
                        [class.shortage]="m.kind === 'STOCK_SHORTAGE'"
                      >
                        @if (m.kind !== 'TEXT') {
                          <p class="bubble-kind">
                            <app-icon
                              [name]="m.kind === 'TRANSFER' ? 'transfer' : 'alert'"
                              [size]="12"
                              [stroke]="2.25"
                            />
                            {{ kindLabel(m.kind) }}
                          </p>
                        }
                        <p class="whitespace-pre-line">{{ m.body }}</p>
                      </div>
                    </div>
                  </li>
                } @empty {
                  <li class="empty-state">
                    <app-icon name="chat" [size]="22" />
                    <p>Aucun message pour l'instant. Lancez la discussion.</p>
                  </li>
                }
              </ol>
            </div>
            <form
              class="flex items-center gap-2 border-t border-line bg-surface p-3"
              (ngSubmit)="send(conversation)"
            >
              <select
                class="select w-32 shrink-0 sm:w-44"
                [(ngModel)]="kind"
                name="kind"
                aria-label="Type de message"
              >
                <option value="TEXT">Message</option>
                <option value="STOCK_SHORTAGE">Rupture</option>
                <option value="HEALTH_ALERT">Alerte sanitaire</option>
              </select>
              <input
                class="input min-w-0 flex-1"
                [(ngModel)]="draft"
                name="draft"
                maxlength="4000"
                placeholder="Pas de données patient identifiantes"
                aria-label="Message"
              />
              <button
                type="submit"
                class="btn btn-primary shrink-0"
                aria-label="Envoyer"
                [disabled]="!draft.trim()"
              >
                <app-icon name="send" [size]="16" />
                <span class="hidden sm:inline">Envoyer</span>
              </button>
            </form>
          } @else {
            <div class="empty-state m-auto">
              <app-icon name="chat" [size]="22" />
              <p class="font-medium text-ink">Choisissez une conversation</p>
              <p>Les messages du réseau s'affichent ici en temps réel.</p>
            </div>
          }
        </section>
      </div>
    </div>
  `,
  styles: `
    .conversation {
      color: var(--text);
      transition: background-color 120ms ease;
    }
    .conversation:hover {
      background: var(--surface-2);
    }
    .conversation-icon {
      background: var(--surface-3);
      color: var(--text-muted);
    }
    .conversation.active {
      background: var(--brand-soft);
      color: var(--text);
    }
    .conversation.active .conversation-icon {
      background: var(--brand-primary);
      color: var(--brand-on-primary);
    }
    .bubble {
      padding: 0.6rem 0.85rem;
      border: 1px solid var(--border);
      border-radius: 1rem;
      border-bottom-left-radius: 0.35rem;
      background: var(--surface);
      color: var(--text);
      font-size: 0.875rem;
      line-height: 1.45;
      box-shadow: var(--shadow-sm);
    }
    .bubble.mine {
      border-color: transparent;
      border-bottom-left-radius: 1rem;
      border-bottom-right-radius: 0.35rem;
      background: var(--brand-primary);
      color: var(--brand-on-primary);
    }
    .bubble.shortage {
      border-color: color-mix(in oklab, var(--warning) 30%, transparent);
      background: var(--warning-soft);
      color: var(--text);
    }
    .bubble.health-alert {
      border-color: color-mix(in oklab, var(--danger-strong) 30%, transparent);
      background: var(--danger-soft);
      color: var(--text);
    }
    .bubble-kind {
      display: flex;
      align-items: center;
      gap: 0.3rem;
      margin-bottom: 0.2rem;
      font-size: 0.7rem;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      opacity: 0.85;
    }
    .bubble.shortage .bubble-kind {
      color: var(--warning);
      opacity: 1;
    }
    .bubble.health-alert .bubble-kind {
      color: var(--danger-strong);
      opacity: 1;
    }
  `,
})
export class ChatPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(NodeInfoService).networkApiUrl()}/v1/chat`;
  protected readonly stomp = inject(StompService);
  protected readonly myTenant = inject(AuthService).tenantId;
  protected readonly conversations = signal<Conversation[]>([]);
  protected readonly active = signal<Conversation | null>(null);
  protected readonly messages = signal<ChatMessage[]>([]);
  protected draft = '';
  protected kind: MessageKind = 'TEXT';
  private live?: Subscription;

  constructor() {
    void this.loadConversations();
    inject(DestroyRef).onDestroy(() => this.live?.unsubscribe());
  }

  protected kindLabel(kind: MessageKind): string {
    return KIND_LABEL[kind];
  }

  protected initials(name: string): string {
    return name
      .replace(/^Dr\.?\s+/i, '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0].toUpperCase())
      .join('');
  }

  protected async open(conversation: Conversation): Promise<void> {
    this.active.set(conversation);
    this.live?.unsubscribe();
    const history = await firstValueFrom(
      this.http.get<ChatMessage[]>(`${this.api}/conversations/${conversation.id}/messages`),
    );
    this.messages.set(history.reverse());
    this.live = this.stomp
      .watch<ChatMessage>(`/topic/conversations.${conversation.id}`)
      .subscribe((message) =>
        this.messages.update((messages) =>
          messages.some((m) => m.id === message.id) ? messages : [...messages, message],
        ),
      );
  }

  protected async send(conversation: Conversation): Promise<void> {
    const body = this.draft.trim();
    if (!body) {
      return;
    }
    const payload = { kind: this.kind, body };
    if (this.stomp.connected()) {
      this.stomp.publish(`/app/conversations.${conversation.id}.messages`, payload);
    } else {
      const message = await firstValueFrom(
        this.http.post<ChatMessage>(
          `${this.api}/conversations/${conversation.id}/messages`,
          payload,
        ),
      );
      this.messages.update((messages) => [...messages, message]);
    }
    this.draft = '';
    this.kind = 'TEXT';
  }

  private async loadConversations(): Promise<void> {
    this.conversations.set(
      await firstValueFrom(this.http.get<Conversation[]>(`${this.api}/conversations`)),
    );
  }
}
