import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { StompService } from '../../core/realtime/stomp.service';

interface Conversation {
  id: string;
  networkId: string;
  kind: 'NETWORK_CHANNEL' | 'DIRECT';
  title: string;
}

type MessageKind = 'TEXT' | 'STOCK_SHORTAGE' | 'HEALTH_ALERT' | 'TRANSFER';

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
  imports: [FormsModule, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid h-full gap-4 p-4 md:grid-cols-[260px_1fr]">
      <aside class="rounded-lg border bg-white">
        <p
          class="border-b p-3 text-sm"
          [class.text-green-700]="stomp.connected()"
          [class.text-amber-700]="!stomp.connected()"
        >
          {{ stomp.connected() ? 'Connecté' : 'Connexion…' }}
        </p>
        <ul>
          @for (c of conversations(); track c.id) {
            <li>
              <button
                type="button"
                class="w-full px-3 py-2 text-left"
                [class.bg-brand]="c.id === active()?.id"
                [class.text-on-brand]="c.id === active()?.id"
                (click)="open(c)"
              >
                {{ c.kind === 'DIRECT' ? '✉' : '#' }} {{ c.title }}
              </button>
            </li>
          }
        </ul>
      </aside>
      <section class="flex min-h-0 flex-col rounded-lg border bg-white">
        @if (active(); as conversation) {
          <header class="border-b p-3 font-semibold">{{ conversation.title }}</header>
          <ol class="flex-1 space-y-2 overflow-auto p-3">
            @for (m of messages(); track m.id) {
              <li [class.text-right]="m.senderTenantId === myTenant()">
                <div
                  class="inline-block max-w-[75%] rounded-lg px-3 py-2 text-left"
                  [class.bg-red-50]="m.kind === 'HEALTH_ALERT'"
                  [class.bg-amber-50]="m.kind === 'STOCK_SHORTAGE'"
                  [class.bg-slate-100]="m.kind === 'TEXT' || m.kind === 'TRANSFER'"
                >
                  <p class="text-xs text-slate-500">
                    {{ m.senderDisplayName }} · {{ m.senderPharmacyName }} ·
                    {{ m.sentAt | date: 'dd/MM HH:mm' }}
                  </p>
                  @if (m.kind !== 'TEXT') {
                    <p class="text-xs font-semibold uppercase">{{ m.kind }}</p>
                  }
                  <p class="whitespace-pre-line">{{ m.body }}</p>
                </div>
              </li>
            }
          </ol>
          <form class="flex gap-2 border-t p-3" (ngSubmit)="send(conversation)">
            <select
              class="rounded border px-2"
              [(ngModel)]="kind"
              name="kind"
              aria-label="Type de message"
            >
              <option value="TEXT">Message</option>
              <option value="STOCK_SHORTAGE">Rupture</option>
              <option value="HEALTH_ALERT">Alerte sanitaire</option>
            </select>
            <input
              class="flex-1 rounded border px-3 py-2"
              [(ngModel)]="draft"
              name="draft"
              maxlength="4000"
              placeholder="Pas de données patient identifiantes"
              aria-label="Message"
            />
            <button
              type="submit"
              class="rounded bg-brand px-4 text-on-brand"
              [disabled]="!draft.trim()"
            >
              Envoyer
            </button>
          </form>
        } @else {
          <p class="m-auto text-slate-500">Choisissez une conversation</p>
        }
      </section>
    </div>
  `,
})
export class ChatPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/chat`;
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
