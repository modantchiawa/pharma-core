import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { NodeInfoService } from '../api/node-info.service';
import { AuthService } from './auth.service';
import { PermissionService } from './permission.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.isAuthenticated() || (!navigator.onLine && inject(PermissionService).profile())) {
    // Offline: a cashier whose token expired keeps selling; sync resumes after re-login.
    return true;
  }
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

export function permissionGuard(...permissions: string[]): CanActivateFn {
  return () => {
    const allowed = inject(PermissionService).hasAny(...permissions);
    return allowed || inject(Router).createUrlTree(['/']);
  };
}

export const platformGuard: CanActivateFn = () =>
  inject(AuthService).isPlatformOperator() || inject(Router).createUrlTree(['/login']);

/** An unpaired hub shows its setup wizard before anything else. */
export const pairedGuard: CanActivateFn = () =>
  inject(NodeInfoService).isPaired() || inject(Router).createUrlTree(['/setup']);
