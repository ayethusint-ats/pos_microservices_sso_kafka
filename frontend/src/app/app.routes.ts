import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './auth/auth.guards';

/**
 * Pages are lazy-loaded. Guards only shape the UI; the backend enforces the same rules
 * independently (see documentation/08-role-based-authorization.md).
 */
export const routes: Routes = [
  {
    path: '',
    title: 'Modern Bank',
    loadComponent: () => import('./pages/home/home.page').then((m) => m.HomePage),
  },
  {
    path: 'profile',
    title: 'My profile',
    canActivate: [authGuard, roleGuard('CUSTOMER')],
    loadComponent: () => import('./pages/profile/profile.page').then((m) => m.ProfilePage),
  },
  {
    path: 'accounts',
    title: 'My accounts',
    canActivate: [authGuard, roleGuard('CUSTOMER')],
    loadComponent: () => import('./pages/accounts/accounts.page').then((m) => m.AccountsPage),
  },
  {
    path: 'payments',
    title: 'Transfers',
    canActivate: [authGuard, roleGuard('CUSTOMER')],
    loadComponent: () => import('./pages/payments/payments.page').then((m) => m.PaymentsPage),
  },
  {
    path: 'backoffice',
    title: 'Back office',
    canActivate: [authGuard, roleGuard('SUPPORT', 'ADMIN')],
    loadComponent: () => import('./pages/backoffice/backoffice.page').then((m) => m.BackofficePage),
  },
  {
    path: 'token',
    title: 'Token inspector',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/token/token.page').then((m) => m.TokenPage),
  },
  {
    path: 'forbidden',
    title: 'Not allowed',
    loadComponent: () => import('./pages/forbidden/forbidden.page').then((m) => m.ForbiddenPage),
  },
  { path: '**', redirectTo: '' },
];
