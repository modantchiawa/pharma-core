import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LicenseStateView } from '../api/models';
import { Icon } from '../../shared/ui/icon';
import { bannerFor } from './license-text';

/** Global banner of the shell for a subscription in warning, grace or blocked state. */
@Component({
  selector: 'app-license-banner',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (banner(); as notice) {
      <div
        class="alert m-4 mb-0 flex-wrap lg:mx-6 print:hidden"
        [class.alert-warning]="notice.tone === 'warning'"
        [class.alert-danger]="notice.tone === 'danger'"
        role="status"
        data-testid="license-banner"
      >
        <app-icon [name]="notice.icon" [size]="18" class="mt-0.5 shrink-0" />
        <span class="min-w-0 flex-1">
          <strong class="font-semibold">{{ notice.title }}</strong>
          <span class="block opacity-90">{{ notice.message }}</span>
        </span>
        @if (link()) {
          <a
            routerLink="/account/subscription"
            class="shrink-0 self-center font-semibold whitespace-nowrap underline max-sm:basis-full max-sm:pl-7"
            >Mon abonnement</a
          >
        }
      </div>
    }
  `,
})
export class LicenseBanner {
  readonly state = input.required<LicenseStateView>();
  /** Whether the user may open the subscription page. */
  readonly link = input(false);
  protected readonly banner = computed(() => bannerFor(this.state()));
}
