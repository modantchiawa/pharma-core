import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { BrandingService } from '../../core/branding/branding.service';
import { POSOLOGY_MOMENTS, Posology, posologyEndsOn } from '../../shared/posology';
import { Icon } from '../../shared/ui/icon';
import { CartLine } from './cart.store';

/**
 * Labels handed to the patient, one per product with a posology: the moments of the day drawn
 * as pictograms (sunrise, sun, sunset, moon) with the number of units in large type, the
 * duration, "with food", and the pharmacist's instruction. Readable without knowing how to
 * read: the drawings carry the message, the words confirm it. Printed on the 58 mm ticket roll
 * (or the 80 mm one), one label per cut.
 */
@Component({
  selector: 'app-posology-labels',
  imports: [DatePipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (label of labels(); track label.line.productId) {
      <article
        class="label bg-white text-black"
        [class.w-[58mm]]="format() === 'MM58'"
        [class.w-[80mm]]="format() === 'MM80'"
      >
        <header class="flex items-start justify-between gap-2 border-b border-black pb-1">
          <div class="min-w-0">
            <p class="truncate text-[10px] font-bold uppercase">{{ identity().displayName }}</p>
            @if (identity().phone) {
              <p class="text-[9px]">Tél. {{ identity().phone }}</p>
            }
          </div>
          <p class="shrink-0 text-[9px]">{{ occurredAt() | date: 'dd/MM/yyyy' }}</p>
        </header>
        @if (patient()) {
          <p class="mt-1 text-[10px]">
            Pour <span class="font-bold">{{ patient() }}</span>
          </p>
        }
        <p class="mt-1 text-[13px] leading-tight font-bold break-words">
          {{ label.line.designation }}
        </p>

        <div class="mt-2 grid grid-cols-4 gap-1 text-center">
          @for (moment of moments; track moment.key) {
            @let units = label.posology[moment.key];
            <div
              class="rounded border border-black py-1"
              [class.opacity-25]="units === 0"
              [class.bg-black]="units > 0"
              [class.text-white]="units > 0"
            >
              <app-icon [name]="moment.icon" [size]="20" class="mx-auto block" />
              <p class="text-[20px] leading-none font-black">{{ units }}</p>
              <p class="text-[8px] uppercase">{{ moment.label }}</p>
            </div>
          }
        </div>

        <div class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
          @if (label.posology.durationDays > 0) {
            <p class="flex items-center gap-1 font-bold">
              <app-icon name="calendarCheck" [size]="14" />
              {{ label.posology.durationDays }}
              {{ label.posology.durationDays > 1 ? 'jours' : 'jour' }}
              @if (label.endsOn; as endsOn) {
                <span class="font-normal">· jusqu'au {{ endsOn | date: 'dd/MM' }}</span>
              }
            </p>
          }
          @if (label.posology.withFood) {
            <p class="flex items-center gap-1">
              <svg
                viewBox="0 0 24 24"
                width="14"
                height="14"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M3 11h18" />
                <path d="M4 11a8 8 0 0 1 16 0" />
                <path d="M5 11v2a7 7 0 0 0 14 0v-2" />
                <path d="M9 21h6" />
              </svg>
              Pendant les repas
            </p>
          }
        </div>
        @if (label.posology.instructions) {
          <p class="mt-1 text-[11px] font-semibold break-words">
            {{ label.posology.instructions }}
          </p>
        }
        <p class="mt-2 border-t border-dashed border-black pt-1 text-[8px] text-slate-700">
          Ticket {{ ticketNumber() }} · Respectez la dose · Tenir hors de portée des enfants
        </p>
      </article>
    }
  `,
  styles: `
    .label {
      padding: 2mm 2.5mm 3mm;
      break-after: page;
      page-break-after: always;
    }
    .label:last-child {
      break-after: auto;
      page-break-after: auto;
    }
  `,
})
export class PosologyLabels {
  readonly lines = input.required<CartLine[]>();
  readonly patient = input<string | null>(null);
  readonly ticketNumber = input('');
  readonly occurredAt = input.required<Date>();
  readonly format = input<'MM58' | 'MM80'>('MM58');

  private readonly branding = inject(BrandingService);
  protected readonly identity = computed(() => this.branding.branding());
  protected readonly moments = POSOLOGY_MOMENTS;

  protected readonly labels = computed(() =>
    this.lines()
      .filter((line): line is CartLine & { posology: Posology } => !!line.posology)
      .map((line) => ({
        line,
        posology: line.posology,
        endsOn: posologyEndsOn(this.occurredAt(), line.posology),
      })),
  );
}
