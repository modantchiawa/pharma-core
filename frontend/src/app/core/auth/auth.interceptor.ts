import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { APP_ENVIRONMENT } from '../config';
import { uuidv7 } from '../util/uuid';
import { AuthService } from './auth.service';

/** Adds the bearer token and a correlation id to API calls; retries once after a silent refresh. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const env = inject(APP_ENVIRONMENT);
  const auth = inject(AuthService);
  if (!request.url.startsWith(env.apiUrl) || request.url.startsWith(`${env.apiUrl}/public/`)) {
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
