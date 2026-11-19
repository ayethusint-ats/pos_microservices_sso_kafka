import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService, Role } from './auth.service';

/**
 * Route guards improve the USER EXPERIENCE: they keep people from navigating to screens that
 * would only show errors. They are NOT security. Anyone can edit JavaScript in the browser or
 * call the API with curl. Real enforcement happens in the gateway and the services.
 */

/** Not logged in → go to Keycloak, then come back to the page that was requested. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.isAuthenticated()) {
    return true;
  }
  auth.login(state.url);
  return false;
};

/** Logged in but without any of the roles → friendly "forbidden" page instead of a broken screen. */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    return roles.some((role) => auth.hasRole(role)) ? true : router.createUrlTree(['/forbidden']);
  };
}
