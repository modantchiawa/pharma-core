import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { LicenseStateService } from './license-state.service';

const LOCKED = 423;

/** A modification refused because the subscription is blocked: the banner catches up at once. */
export const licenseInterceptor: HttpInterceptorFn = (request, next) => {
  const license = inject(LicenseStateService);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === LOCKED &&
        (error.error as { code?: string } | null)?.code === 'license-blocked'
      ) {
        void license.refresh();
      }
      return throwError(() => error);
    }),
  );
};
