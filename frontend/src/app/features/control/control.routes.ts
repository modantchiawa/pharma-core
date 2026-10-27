import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/guards';

/** Internal control screens, loaded on first visit. */
export const CONTROL_ROUTES: Routes = [
  {
    path: '',
    title: 'Contrôle interne',
    canActivate: [permissionGuard('CONTROL_VIEW')],
    loadComponent: () => import('./control-page').then((m) => m.ControlPage),
  },
  {
    path: 'alerts',
    title: 'Alertes',
    canActivate: [permissionGuard('CONTROL_VIEW')],
    loadComponent: () => import('./alerts-page').then((m) => m.AlertsPage),
  },
  {
    path: 'settings',
    title: 'Règles du contrôle interne',
    canActivate: [permissionGuard('CONTROL_SETTINGS')],
    loadComponent: () => import('./control-settings-page').then((m) => m.ControlSettingsPage),
  },
];
