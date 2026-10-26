import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { APP_ENVIRONMENT } from '../config';
import { uuidv7 } from '../util/uuid';
import { NodeInfoService } from '../api/node-info.service';
import { AuthService } from './auth.service';

/** Adds the bearer token and a correlation id to API calls; retries once after a silent refresh. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const env = inject(APP_ENVIRONMENT);
  const auth = inject(AuthService);
  const node = inject(NodeInfoService);
  const targets = [env.apiUrl, node.networkApiUrl()];
  const isApi = targets.some((base) => request.url.startsWith(base));
  const isPublic = request.url.includes('/api/public/') || request.url.includes('/api/auth/');
  if (!isApi || isPublic) {
    return next(request);
  }
  const withHeaders = (token: string | null) =>
    request.clone({
      setHeaders: {
        'X-Correlation-Id': uuidv7(),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });

  return next(withHeaders(auth.accessToken())).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        return from(auth.refresh()).pipe(
          switchMap((token) => (token ? next(withHeaders(token)) : throwError(() => error))),
        );
      }
      return throwError(() => error);
    }),
  );
};
