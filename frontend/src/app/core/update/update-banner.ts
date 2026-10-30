import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { AppUpdateService } from './app-update.service';
import { versionLabel } from './build-info';

/** "Nouvelle version disponible" across the top of every screen until the station updates. */
@Component({
  selector: 'app-update-banner',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <div
        class="alert alert-info m-4 mb-0 flex-wrap items-center lg:mx-6 print:hidden"
        role="alert"
        data-testid="update-banner"
      >
        <app-icon name="sparkles" [size]="18" />
        <span class="min-w-0 flex-1">
          <strong>Nouvelle version de PharmaCore disponible</strong>
          @if (latest(); as next) {
            ({{ next }})
          }
          — mettez à jour pour profiter des corrections. Les ventes en attente et le panier sont
          conservés.
        </span>
        <span class="flex gap-2">
          <button
            type="button"
            class="btn btn-primary btn-sm"
            [disabled]="updates.updating()"
            (click)="updates.update()"
            data-testid="update-now"
          >
            <app-icon name="refresh" [size]="14" />
            {{ updates.updating() ? 'Mise à jour…' : 'Mettre à jour' }}
          </button>
          <button type="button" class="btn btn-ghost btn-sm" (click)="updates.snooze()">
            Plus tard
          </button>
        </span>
      </div>
    }
  `,
})
export class UpdateBanner {
  protected readonly updates = inject(AppUpdateService);
  protected readonly visible = computed(
    () => this.updates.available() && Date.now() >= this.updates.snoozedUntil(),
  );
  protected readonly latest = computed(() => {
    const next = this.updates.latest();
    return next ? versionLabel(next) : null;
  });
}
