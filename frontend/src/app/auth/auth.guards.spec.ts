import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard, roleGuard } from './auth.guards';
import { AuthService, Role } from './auth.service';

describe('route guards', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/accounts' } as RouterStateSnapshot;

  function setup(authenticated: boolean, roles: Role[]) {
    const auth = {
      isAuthenticated: () => authenticated,
      hasRole: (role: Role) => roles.includes(role),
      login: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    return auth;
  }

  it('authGuard lets authenticated users through', () => {
    setup(true, ['CUSTOMER']);
    const result = TestBed.runInInjectionContext(() => authGuard(route, state));
    expect(result).toBe(true);
  });

  it('authGuard sends anonymous users to login and remembers the target page', () => {
    const auth = setup(false, []);
    const result = TestBed.runInInjectionContext(() => authGuard(route, state));
    expect(result).toBe(false);
    expect(auth.login).toHaveBeenCalledWith('/accounts');
  });

  it('roleGuard allows users with the role', () => {
    setup(true, ['ADMIN']);
    const result = TestBed.runInInjectionContext(() => roleGuard('ADMIN')(route, state));
    expect(result).toBe(true);
  });

  it('roleGuard redirects users without the role to /forbidden', () => {
    setup(true, ['CUSTOMER']);
    const result = TestBed.runInInjectionContext(() => roleGuard('ADMIN')(route, state));
    const router = TestBed.inject(Router);
    expect(result instanceof UrlTree).toBe(true);
    expect(router.serializeUrl(result as UrlTree)).toBe('/forbidden');
  });
});
