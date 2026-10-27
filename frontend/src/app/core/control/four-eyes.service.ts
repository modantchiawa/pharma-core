import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FourEyesThresholds } from '../api/models';
import { APP_ENVIRONMENT } from '../config';

/**
 * Four-eyes thresholds of the pharmacy, so the screens ask for the second signature before
 * sending the request (the server enforces the same rule and has the last word).
 */
@Injectable({ providedIn: 'root' })
export class FourEyesThresholdsService {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/control/four-eyes`;
  private readonly state = signal<FourEyesThresholds | null>(null);
  private loading: Promise<FourEyesThresholds | null> | null = null;

  readonly thresholds = this.state.asReadonly();

  load(): Promise<FourEyesThresholds | null> {
    this.loading ??= firstValueFrom(this.http.get<FourEyesThresholds>(this.api))
      .then((thresholds) => {
        this.state.set(thresholds);
        return thresholds;
      })
      .catch(() => {
        this.loading = null;
        return null;
      });
    return this.loading;
  }
}
