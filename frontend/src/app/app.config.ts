import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { PermissionService } from './core/auth/permission.service';
import { BrandingService } from './core/branding/branding.service';
import { SyncOrchestratorService } from './core/offline/sync-orchestrator.service';

async function bootstrapSession(): Promise<void> {
  const auth = inject(AuthService);
  const permissions = inject(PermissionService);
  const branding = inject(BrandingService);
  const sync = inject(SyncOrchestratorService);
  await auth.init();
  if (auth.isPlatformOperator()) {
    return;
  }
  if (auth.isAuthenticated()) {
    await permissions.load();
    await branding.load();
  }
  // Offline restart with a cached profile: the POS keeps selling from IndexedDB.
  if (permissions.profile()) {
    sync.start();
  }
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAppInitializer(bootstrapSession),
    provideServiceWorker('sw.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
