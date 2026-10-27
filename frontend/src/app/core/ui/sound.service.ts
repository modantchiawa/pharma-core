import { Injectable, inject } from '@angular/core';
import { DisplayPreferencesService } from './display-preferences.service';

interface Note {
  frequency: number;
  at: number;
  length: number;
}

/** Each cue is a couple of soft sine notes, a few hundredths of a second long. */
const CUES: Record<'scan' | 'sale' | 'refusal', Note[]> = {
  scan: [{ frequency: 1320, at: 0, length: 0.05 }],
  sale: [
    { frequency: 880, at: 0, length: 0.09 },
    { frequency: 1318.5, at: 0.08, length: 0.16 },
  ],
  refusal: [
    { frequency: 330, at: 0, length: 0.09 },
    { frequency: 262, at: 0.1, length: 0.14 },
  ],
};
const VOLUME = 0.035;

/**
 * Very quiet counter cues (item scanned, sale recorded, scan refused), synthesized on the fly:
 * no audio file, nothing loaded until the first cue. Off unless the terminal turned them on in
 * its display preferences.
 */
@Injectable({ providedIn: 'root' })
export class SoundService {
  private readonly display = inject(DisplayPreferencesService);
  private context: AudioContext | null = null;

  play(cue: keyof typeof CUES): void {
    if (!this.display.preferences().sounds || typeof AudioContext === 'undefined') {
      return;
    }
    try {
      this.context ??= new AudioContext();
      const context = this.context;
      if (context.state === 'suspended') {
        void context.resume();
      }
      const start = context.currentTime + 0.01;
      for (const note of CUES[cue]) {
        const oscillator = context.createOscillator();
        const gain = context.createGain();
        oscillator.type = 'sine';
        oscillator.frequency.value = note.frequency;
        const from = start + note.at;
        gain.gain.setValueAtTime(0, from);
        gain.gain.linearRampToValueAtTime(VOLUME, from + 0.008);
        gain.gain.exponentialRampToValueAtTime(0.0001, from + note.length);
        oscillator.connect(gain).connect(context.destination);
        oscillator.start(from);
        oscillator.stop(from + note.length + 0.02);
      }
    } catch {
      // audio unavailable on this terminal: the visual feedback is enough
    }
  }
}
