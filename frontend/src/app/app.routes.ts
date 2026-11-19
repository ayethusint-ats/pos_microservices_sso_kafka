import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './auth/auth.guards';

/** Pages are lazy-loaded: their code is downloaded only when the route is first visited. */
export const routes: Routes = [
  {
    path: '',
    title: 'Modern Bank',
    loadComponent: () => import('./pages/home/home.page').then((m) => m.HomePage),
  },
  {
    path: 'accounts',
    title: 'My accounts',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/accounts/accounts.page').then((m) => m.AccountsPage),
  },
  {
    path: 'token',
    title: 'Token inspector',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/token/token.page').then((m) => m.TokenPage),
  },
  {
    path: 'admin',
    title: 'Administration',
    canActivate: [authGuard, roleGuard('ADMIN')],
    loadComponent: () => import('./pages/admin/admin.page').then((m) => m.AdminPage),
  },
  {
    path: 'forbidden',
    title: 'Not allowed',
    loadComponent: () => import('./pages/forbidden/forbidden.page').then((m) => m.ForbiddenPage),
  },
  { path: '**', redirectTo: '' },
];
