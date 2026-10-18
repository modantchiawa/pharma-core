import { Injectable, computed, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { PosDatabase } from './pos-database';

/** Opens the IndexedDB database of the current pharmacy (from the token, or the cached profile offline). */
@Injectable({ providedIn: 'root' })
export class PosDatabaseService {
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly databases = new Map<string, PosDatabase>();
  private readonly override = signal<string | null>(null);

  readonly tenantId = computed(
    () => this.override() ?? this.auth.tenantId() ?? this.permissions.profile()?.tenantId ?? null,
  );

  db(): PosDatabase {
    const tenant = this.tenantId();
    if (!tenant) {
      throw new Error('No pharmacy context: sign in at least once on this terminal');
    }
    let database = this.databases.get(tenant);
    if (!database) {
      database = new PosDatabase(tenant);
      this.databases.set(tenant, database);
    }
    return database;
  }

  /** Test hook and multi-pharmacy terminals. */
  useTenant(tenantId: string | null): void {
    this.override.set(tenantId);
  }
}
