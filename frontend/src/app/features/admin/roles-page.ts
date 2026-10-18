import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { APP_ENVIRONMENT } from '../../core/config';

interface Role {
  id: string;
  code: string;
  name: string;
  systemRole: boolean;
  maxDiscountPercent: number;
  permissions: string[];
  version: number;
}

/** Permission matrix of the pharmacy: one column per role, one row per permission. */
@Component({
  selector: 'app-roles-page',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="overflow-auto p-4">
      <table class="rounded border bg-white text-sm">
        <thead class="bg-slate-100">
          <tr>
            <th class="p-2 text-left">Permission</th>
            @for (role of roles(); track role.id) {
              <th class="p-2">{{ role.name }}</th>
            }
          </tr>
        </thead>
        <tbody>
          <tr class="border-t">
            <td class="p-2 font-medium">Plafond de remise (%)</td>
            @for (role of roles(); track role.id) {
              <td class="p-2 text-center">
                <input
                  type="number"
                  min="0"
                  max="100"
                  class="w-16 rounded border px-1"
                  [ngModel]="role.maxDiscountPercent"
                  (ngModelChange)="setDiscount(role, $event)"
                  [attr.aria-label]="'Plafond ' + role.name"
                />
              </td>
            }
          </tr>
          @for (permission of permissions(); track permission) {
            <tr class="border-t">
              <td class="p-2 font-mono">{{ permission }}</td>
              @for (role of roles(); track role.id) {
                <td class="p-2 text-center">
                  <input
                    type="checkbox"
                    [checked]="role.permissions.includes(permission)"
                    (change)="toggle(role, permission)"
                    [attr.aria-label]="permission + ' pour ' + role.name"
                  />
                </td>
              }
            </tr>
          }
        </tbody>
      </table>
      <div class="mt-3 flex items-center gap-3">
        <button
          type="button"
          class="rounded bg-brand px-4 py-2 text-on-brand"
          [disabled]="dirty().size === 0"
          (click)="save()"
        >
          Enregistrer
        </button>
        @if (status()) {
          <span role="status">{{ status() }}</span>
        }
      </div>
    </div>
  `,
})
export class RolesPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1`;
  protected readonly roles = signal<Role[]>([]);
  protected readonly permissions = signal<string[]>([]);
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
