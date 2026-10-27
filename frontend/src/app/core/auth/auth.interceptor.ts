import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { APP_ENVIRONMENT } from '../config';
import { uuidv7 } from '../util/uuid';
import { NodeInfoService } from '../api/node-info.service';
import {
  BACKGROUND_HEADER,
  isBackgroundRequest,
  problemCode,
} from '../session/background-requests';
import { SessionSignals } from '../session/session-signals';
import { AuthService } from './auth.service';

const LOCKED = 423;
const UNAUTHORIZED = 401;

/**
 * Adds the bearer token, a correlation id and the background mark to API calls; retries once
 * after a silent refresh. The server's verdict on the session reaches the lock screen from here:
 * 423 `session-locked` locks this tab, 401 `session-expired` closes the session.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const env = inject(APP_ENVIRONMENT);
  const auth = inject(AuthService);
  const node = inject(NodeInfoService);
  const signals = inject(SessionSignals);
  const targets = [env.apiUrl, node.networkApiUrl()];
  const isApi = targets.some((base) => request.url.startsWith(base));
  const isPublic = request.url.includes('/api/public/') || request.url.includes('/api/auth/');
  if (!isApi || isPublic) {
    return next(request);
  }
  const background = isBackgroundRequest(request.method, request.url);
  if (!background) {
    signals.lastActivityRequest = Date.now();
  }
  const withHeaders = (token: string | null) =>
    request.clone({
      setHeaders: {
        'X-Correlation-Id': uuidv7(),
        ...(background ? { [BACKGROUND_HEADER]: '1' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
  const relay = (error: unknown) => {
    if (error instanceof HttpErrorResponse) {
      const code = problemCode(error.error);
      if (error.status === LOCKED && code === 'session-locked') {
        signals.serverLocked();
      } else if (error.status === UNAUTHORIZED && code === 'session-expired') {
        signals.serverClosed();
      }
    }
    return throwError(() => error);
  };

  return next(withHeaders(auth.accessToken())).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === UNAUTHORIZED &&
        problemCode(error.error) !== 'session-expired'
      ) {
        return from(auth.refresh()).pipe(
          switchMap((token) =>
            token ? next(withHeaders(token)).pipe(catchError(relay)) : throwError(() => error),
          ),
        );
      }
      return relay(error);
    }),
  );
};
