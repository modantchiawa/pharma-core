import { Pipe, PipeTransform, inject } from '@angular/core';
import { TerminalNamesService } from '../core/offline/terminal-names.service';

/** `{{ terminalId | terminalLabel }}` → "Caisse 1 (T7K2MX9)"; `terminalLabel: false` → "Caisse 1". */
@Pipe({ name: 'terminalLabel', pure: false })
export class TerminalLabelPipe implements PipeTransform {
  private readonly names = inject(TerminalNamesService);

  transform(terminalId: string | null | undefined, withId = true): string {
    return this.names.label(terminalId, withId);
  }
}
