import { computed, inject, Injectable } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { map, Observable, of, switchMap, take } from 'rxjs';

export type Role = 'CUSTOMER' | 'SUPPORT' | 'ADMIN';

const POST_LOGIN_REDIRECT_KEY = 'banking.postLoginRedirect';
const KNOWN_ROLES: readonly Role[] = ['CUSTOMER', 'SUPPORT', 'ADMIN'];

/** Claims of the ACCESS token that the UI cares about. */
export interface AccessTokenClaims {
  sub: string;
  preferred_username?: string;
  exp: number;
  iat: number;
  aud?: string | string[];
  azp?: string;
  scope?: string;
  realm_access?: { roles?: string[] };
  [claim: string]: unknown;
}

/**
 * A thin facade over the OIDC library, so components and guards don't depend on it directly.
 *
 * <p>Roles are read from the access token ONLY to adapt the UI (show or hide menu items).
 * They are never a security boundary: the user controls the browser. The gateway and services
 * validate the token and enforce authorization on every request.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oidc = inject(OidcSecurityService);
  private readonly router = inject(Router);

  readonly isAuthenticated = toSignal(this.oidc.isAuthenticated$.pipe(map((r) => r.isAuthenticated)), {
    initialValue: false,
  });

  /** Re-read on every auth state change, including silent refreshes. */
  readonly accessTokenClaims = toSignal(
    this.oidc.isAuthenticated$.pipe(
      switchMap(({ isAuthenticated }) =>
        isAuthenticated ? (this.oidc.getPayloadFromAccessToken() as Observable<AccessTokenClaims>) : of(null),
      ),
    ),
    { initialValue: null },
  );

  readonly idTokenClaims = toSignal(
    this.oidc.isAuthenticated$.pipe(
      switchMap(({ isAuthenticated }) =>
        isAuthenticated ? (this.oidc.getPayloadFromIdToken() as Observable<Record<string, unknown>>) : of(null),
      ),
    ),
    { initialValue: null },
  );

  readonly userId = computed(() => this.accessTokenClaims()?.sub ?? null);
  readonly username = computed(() => this.accessTokenClaims()?.preferred_username ?? null);
  readonly roles = computed<Role[]>(() =>
    (this.accessTokenClaims()?.realm_access?.roles ?? []).filter((r): r is Role =>
      (KNOWN_ROLES as readonly string[]).includes(r),
    ),
  );

  hasRole(role: Role): boolean {
    return this.roles().includes(role);
  }

  /** Staff = back-office users (SUPPORT or ADMIN). */
  readonly isStaff = computed(() => this.roles().some((r) => r === 'SUPPORT' || r === 'ADMIN'));

  /** Redirects the browser to Keycloak. `returnUrl` is restored after the login callback. */
  login(returnUrl?: string): void {
    if (returnUrl) {
      sessionStorage.setItem(POST_LOGIN_REDIRECT_KEY, returnUrl);
    }
    this.oidc.authorize();
  }

  /**
   * Single logout: redirects to Keycloak's end_session endpoint with id_token_hint, which ends
   * the SSO session (every app in the realm is logged out), then comes back to the home page.
   * Tokens in this browser are removed as well.
   */
  logout(): void {
    sessionStorage.removeItem(POST_LOGIN_REDIRECT_KEY);
    this.oidc.logoff().pipe(take(1)).subscribe();
  }

  /** Ends only the local session (tokens removed here, Keycloak SSO session untouched). */
  logoutLocally(): void {
    this.oidc.logoffLocal();
  }

  /** Used by the Token page to demonstrate refresh-token rotation on demand. */
  refreshNow(): Observable<unknown> {
    return this.oidc.forceRefreshSession().pipe(take(1));
  }

  /** Called once at startup, after the library has processed a possible login callback. */
  restorePostLoginRedirect(): void {
    const target = sessionStorage.getItem(POST_LOGIN_REDIRECT_KEY);
    if (target && this.isAuthenticated()) {
      sessionStorage.removeItem(POST_LOGIN_REDIRECT_KEY);
      void this.router.navigateByUrl(target);
    }
  }
}
