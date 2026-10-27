import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { APP_ENVIRONMENT } from '../../core/config';
import { Icon } from '../../shared/ui/icon';

interface Role {
  id: string;
  code: string;
  name: string;
  systemRole: boolean;
  maxDiscountPercent: number;
  permissions: string[];
  version: number;
}

/** Rows of the matrix, by functional area; permissions are matched on the prefix of their code. */
const PERMISSION_GROUPS: { label: string; prefixes: string[] }[] = [
  { label: 'Vente et caisse', prefixes: ['SALE', 'CASH', 'CREDIT', 'PAYMENT', 'CUSTOMER'] },
  { label: 'Ordonnances et tiers payant', prefixes: ['PRESCRIPTION', 'NARCOTIC', 'INSURANCE'] },
  { label: 'Stock et achats', prefixes: ['STOCK', 'PROCUREMENT', 'DATA'] },
  { label: 'Réseau et gardes', prefixes: ['NETWORK', 'GUARD', 'DIRECTORY'] },
  { label: 'Pilotage et comptabilité', prefixes: ['DASHBOARD', 'REPORT', 'ACCOUNTING'] },
  { label: 'Contrôle interne', prefixes: ['CONTROL'] },
  { label: 'Administration', prefixes: ['CONFIG', 'USER', 'ROLE', 'AUDIT', 'HUB', 'PRINTER'] },
];

/** Permission matrix of the pharmacy: one column per role, one row per permission. */
@Component({
  selector: 'app-roles-page',
  imports: [FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Rôles et droits</h1>
          <p class="page-subtitle">
            Ce que chaque rôle peut faire dans la pharmacie, et la remise maximale qu'il peut
            accorder en caisse.
          </p>
        </div>
        <div class="flex items-center gap-3">
          @if (dirty().size > 0) {
            <span class="badge badge-warning"
              ><span class="dot"></span>{{ dirty().size }} rôle(s) modifié(s)</span
            >
          }
          <button
            type="button"
            class="btn btn-primary"
            [disabled]="dirty().size === 0"
            (click)="save()"
          >
            <app-icon name="check" [size]="16" />
            Enregistrer
          </button>
        </div>
      </header>

      @if (status()) {
        <p class="alert alert-info mb-4" role="status">
          <app-icon name="info" [size]="18" />
          <span>{{ status() }}</span>
        </p>
      }

      <section class="card overflow-hidden">
        <div class="card-header">
          <div>
            <h2 class="card-title">Matrice des droits</h2>
            <p class="text-xs text-muted">
              {{ permissions().length }} permissions · {{ roles().length }} rôles
            </p>
          </div>
        </div>
        <div class="overflow-x-auto">
          <table class="data-table matrix">
            <thead>
              <tr>
                <th>Permission</th>
                @for (role of roles(); track role.id) {
                  <th class="role-col">
                    <span class="block normal-case tracking-normal text-ink">{{ role.name }}</span>
                    <span class="mt-1.5 flex justify-center gap-1 normal-case tracking-normal">
                      @if (dirty().has(role.id)) {
                        <span class="badge badge-warning">modifié</span>
                      } @else {
                        <span class="badge" [attr.title]="role.systemRole ? 'Rôle système' : null">
                          @if (role.systemRole) {
                            <app-icon name="lock" [size]="11" />
                          }
                          {{ role.permissions.length }} droits
                        </span>
                      }
                    </span>
                  </th>
                }
              </tr>
            </thead>
            <tbody>
              <tr class="discount-row">
                <td class="font-medium">Plafond de remise (%)</td>
                @for (role of roles(); track role.id) {
                  <td class="text-center">
                    <span class="relative inline-flex items-center">
                      <input
                        type="number"
                        min="0"
                        max="100"
                        class="input w-20 pr-6 text-right"
                        [ngModel]="role.maxDiscountPercent"
                        (ngModelChange)="setDiscount(role, $event)"
                        [attr.aria-label]="'Plafond ' + role.name"
                      />
                      <span class="pointer-events-none absolute right-2.5 text-xs text-subtle"
                        >%</span
                      >
                    </span>
                  </td>
                }
              </tr>
              @for (group of groups(); track group.label) {
                <tr class="group-row">
                  <td [attr.colspan]="roles().length + 1">{{ group.label }}</td>
                </tr>
                @for (permission of group.permissions; track permission) {
                  <tr>
                    <td>
                      <code class="text-[0.8rem] text-muted">{{ permission }}</code>
                    </td>
                    @for (role of roles(); track role.id) {
                      <td class="text-center">
                        <input
                          type="checkbox"
                          class="align-middle"
                          [checked]="role.permissions.includes(permission)"
                          (change)="toggle(role, permission)"
                          [attr.aria-label]="permission + ' pour ' + role.name"
                        />
                      </td>
                    }
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        @if (roles().length === 0) {
          <div class="empty-state">
            <app-icon name="roles" [size]="22" />
            <p>Chargement des rôles…</p>
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .matrix th:first-child,
    .matrix td:first-child {
      position: sticky;
      left: 0;
      z-index: 1;
      min-width: 9rem;
      max-width: 11rem;
      overflow-wrap: anywhere;
      background: var(--surface);
      box-shadow: inset -1px 0 0 var(--border);
    }
    @media (min-width: 640px) {
      .matrix th:first-child,
      .matrix td:first-child {
        min-width: 16rem;
        max-width: none;
      }
    }
    .matrix thead th:first-child {
      z-index: 2;
      background: var(--surface-2);
    }
    .matrix tbody tr:hover td:first-child {
      background: var(--surface-2);
    }
    .matrix .role-col {
      min-width: 8.5rem;
      text-align: center;
      vertical-align: bottom;
    }
    .matrix .discount-row td {
      background: var(--brand-softer);
    }
    .matrix .group-row td,
    .matrix tbody tr.group-row:hover td {
      padding-top: 1.1rem;
      padding-bottom: 0.4rem;
      max-width: none;
      background: var(--surface);
      box-shadow: none;
      font-size: 0.7rem;
      font-weight: 650;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-subtle);
    }
    .matrix input[type='checkbox'] {
      width: 1.05rem;
      height: 1.05rem;
      cursor: pointer;
    }
  `,
})
export class RolesPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1`;
  protected readonly roles = signal<Role[]>([]);
  protected readonly permissions = signal<string[]>([]);
  protected readonly groups = computed(() => {
    const prefix = (permission: string) => permission.split('_')[0];
    const groups = PERMISSION_GROUPS.map((group) => ({
      label: group.label,
      permissions: this.permissions().filter((p) => group.prefixes.includes(prefix(p))),
    }));
    const grouped = new Set(groups.flatMap((group) => group.permissions));
    groups.push({
      label: 'Autres droits',
      permissions: this.permissions().filter((p) => !grouped.has(p)),
    });
    return groups.filter((group) => group.permissions.length > 0);
  });
  protected readonly dirty = signal(new Set<string>());
  protected readonly status = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected toggle(role: Role, permission: string): void {
    this.update(role, {
      permissions: role.permissions.includes(permission)
        ? role.permissions.filter((p) => p !== permission)
        : [...role.permissions, permission],
    });
  }

  protected setDiscount(role: Role, value: number): void {
    this.update(role, { maxDiscountPercent: Math.min(Math.max(Number(value) || 0, 0), 100) });
  }

  protected async save(): Promise<void> {
    try {
      for (const role of this.roles().filter((r) => this.dirty().has(r.id))) {
        await firstValueFrom(
          this.http.put(`${this.api}/roles/${role.id}`, {
            code: role.code,
            name: role.name,
            maxDiscountPercent: role.maxDiscountPercent,
            permissions: role.permissions,
          }),
        );
      }
      this.status.set('Matrice enregistrée');
      await this.load();
    } catch {
      this.status.set(
        'Refusé : le rôle titulaire doit conserver la gestion des rôles et des utilisateurs',
      );
    }
  }

  private update(role: Role, change: Partial<Role>): void {
    this.roles.update((roles) => roles.map((r) => (r.id === role.id ? { ...r, ...change } : r)));
    this.dirty.update((ids) => new Set(ids).add(role.id));
  }

  private async load(): Promise<void> {
    const [roles, permissions] = await Promise.all([
      firstValueFrom(this.http.get<Role[]>(`${this.api}/roles`)),
      firstValueFrom(this.http.get<string[]>(`${this.api}/permissions`)),
    ]);
    this.roles.set(roles);
    this.permissions.set(permissions);
    this.dirty.set(new Set());
  }
}
