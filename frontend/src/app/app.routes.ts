import { Routes } from '@angular/router';
import { authGuard, permissionGuard, platformGuard } from './core/auth/guards';
import { Shell } from './shell';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'auth/callback',
    loadComponent: () =>
      import('./features/auth/auth-callback-page').then((m) => m.AuthCallbackPage),
  },
  {
    path: 'platform',
    component: Shell,
    canActivate: [platformGuard],
    children: [
      {
        path: '',
        title: 'Supervision',
        loadComponent: () =>
          import('./features/platform/platform-dashboard-page').then(
            (m) => m.PlatformDashboardPage,
          ),
      },
    ],
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'pos' },
      {
        path: 'pos',
        title: 'Caisse',
        canActivate: [permissionGuard('SALE_CREATE')],
        loadComponent: () => import('./features/pos/pos-page').then((m) => m.PosPage),
      },
      {
        path: 'inventory',
        title: 'Stocks',
        canActivate: [permissionGuard('STOCK_VIEW')],
        loadComponent: () =>
          import('./features/inventory/inventory-page').then((m) => m.InventoryPage),
      },
      {
        path: 'network',
        title: 'Réseau',
        canActivate: [
          permissionGuard(
            'NETWORK_STOCK_QUERY',
            'NETWORK_TRANSFER_REQUEST',
            'NETWORK_TRANSFER_RESPOND',
          ),
        ],
        loadComponent: () => import('./features/network/network-page').then((m) => m.NetworkPage),
      },
      {
        path: 'guards',
        title: 'Gardes',
        canActivate: [permissionGuard('GUARD_VIEW')],
        loadComponent: () => import('./features/guard/guard-page').then((m) => m.GuardPage),
      },
      {
        path: 'chat',
        title: 'Messagerie',
        canActivate: [permissionGuard('NETWORK_CHAT')],
        loadComponent: () => import('./features/chat/chat-page').then((m) => m.ChatPage),
      },
      {
        path: 'admin/branding',
        title: 'Identité visuelle',
        canActivate: [permissionGuard('CONFIG_BRANDING_MANAGE')],
        loadComponent: () =>
          import('./features/admin/branding-settings-page').then((m) => m.BrandingSettingsPage),
      },
      {
        path: 'admin/roles',
        title: 'Rôles et droits',
        canActivate: [permissionGuard('ROLE_MANAGE')],
        loadComponent: () => import('./features/admin/roles-page').then((m) => m.RolesPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
