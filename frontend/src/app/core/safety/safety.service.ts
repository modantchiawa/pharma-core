import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { SafetyAlert, SafetyCheckResult, SafetyRulePage } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { BasketItem, evaluateBasket, sortAlerts } from '../../shared/safety-rules';
import { CachedSafetyRule } from '../offline/pos-database';
import { PosDatabaseService } from '../offline/pos-database.service';

const CURSOR_KEY = 'safetyRulesCursor';
const PAGE_SIZE = 500;

interface RulesCursor {
  since: string;
  after: string | null;
}

/** What the counter shows: the alerts, and whether the customer's file could be consulted. */
export interface SafetyVerdict {
  alerts: SafetyAlert[];
  /** The server answered: allergies, situations and history were checked. */
  serverChecked: boolean;
  profileKnown: boolean;
}

/**
 * Counter vigilance on the terminal. The referential is pulled incrementally into IndexedDB so
 * interactions and duplicates are found even when the server is unreachable; the server adds the
 * customer-specific checks whenever it answers.
 */
@Injectable({ providedIn: 'root' })
export class SafetyService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly databases = inject(PosDatabaseService);
  private running: Promise<number> | null = null;

  readonly ruleCount = signal<number | null>(null);

  pullRules(): Promise<number> {
    this.running ??= this.doPull().finally(() => (this.running = null));
    return this.running;
  }

  async check(customerId: string | null, items: BasketItem[]): Promise<SafetyVerdict> {
    if (items.length === 0) {
      return { alerts: [], serverChecked: true, profileKnown: false };
    }
    try {
      const result = await firstValueFrom(
        this.http.post<SafetyCheckResult>(`${this.env.apiUrl}/v1/safety/check`, {
          customerId,
          productIds: items.map((item) => item.productId),
        }),
      );
      return {
        alerts: sortAlerts(result.alerts),
        serverChecked: true,
        profileKnown: result.profileKnown,
      };
    } catch {
      return { alerts: await this.checkLocally(items), serverChecked: false, profileKnown: false };
    }
  }

  /** Interactions and duplicates from the local referential only. */
  async checkLocally(items: BasketItem[]): Promise<SafetyAlert[]> {
    const rules = await this.databases.db().safetyRules.toArray();
    return evaluateBasket(
      items,
      rules.map((rule) => ({
        kind: rule.kind,
        substance: rule.substance,
        counterpart: rule.counterpart,
        severity: rule.severity,
        advice: rule.advice,
      })),
    );
  }

  private async doPull(): Promise<number> {
    const db = this.databases.db();
    let cursor = ((await db.meta.get(CURSOR_KEY))?.value as RulesCursor | undefined) ?? {
      since: '1970-01-01T00:00:00Z',
      after: null,
    };
    let total = 0;
    for (;;) {
      let params = new HttpParams().set('since', cursor.since).set('limit', PAGE_SIZE);
      if (cursor.after) {
        params = params.set('after', cursor.after);
      }
      const page = await firstValueFrom(
        this.http.get<SafetyRulePage>(`${this.env.apiUrl}/v1/safety/rules`, { params }),
      );
      const next: RulesCursor = { since: page.nextSince, after: page.nextAfter };
      await db.transaction('rw', db.safetyRules, db.meta, async () => {
        const active: CachedSafetyRule[] = [];
        const gone: string[] = [];
        for (const row of page.items) {
          if (row.active) {
            active.push({ id: row.id, ...row.rule, updatedAt: row.updatedAt });
          } else {
            gone.push(row.id);
          }
        }
        await db.safetyRules.bulkPut(active);
        await db.safetyRules.bulkDelete(gone);
        await db.meta.put({ key: CURSOR_KEY, value: next });
      });
      total += page.items.length;
      cursor = next;
      if (!page.hasMore) {
        break;
      }
    }
    this.ruleCount.set(await db.safetyRules.count());
    return total;
  }
}
