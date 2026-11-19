import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService, Role } from './auth/auth.service';

describe('App shell', () => {
  function render(authenticated: boolean, roles: Role[] = []) {
    const auth = {
      isAuthenticated: signal(authenticated),
      username: signal(authenticated ? 'alice' : null),
      roles: signal(roles),
      hasRole: (role: Role) => roles.includes(role),
      isStaff: signal(roles.includes('SUPPORT') || roles.includes('ADMIN')),
      login: vi.fn(),
      logout: vi.fn(),
      restorePostLoginRedirect: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return { element: fixture.nativeElement as HTMLElement, auth };
  }

  it('shows a login button when signed out', () => {
    const { element } = render(false);
    expect(element.querySelector('[data-testid="login"]')).toBeTruthy();
    expect(element.querySelector('nav')).toBeNull();
  });

  it('shows user, roles and logout when signed in', () => {
    const { element } = render(true, ['CUSTOMER']);
    expect(element.querySelector('[data-testid="current-user"]')?.textContent).toContain('alice');
    expect(element.textContent).toContain('CUSTOMER');
    expect(element.querySelector('[data-testid="logout"]')).toBeTruthy();
  });

  it('shows menu items by role (UX only, the backend enforces it)', () => {
    const customer = render(true, ['CUSTOMER']).element.textContent;
    expect(customer).toContain('Accounts');
    expect(customer).not.toContain('Back office');
    TestBed.resetTestingModule();

    const support = render(true, ['SUPPORT']).element.textContent;
    expect(support).toContain('Back office');
    expect(support).not.toContain('Accounts');
  });

  it('logout calls the auth service', () => {
    const { element, auth } = render(true, ['CUSTOMER']);
    (element.querySelector('[data-testid="logout"]') as HTMLButtonElement).click();
    expect(auth.logout).toHaveBeenCalled();
  });
});
