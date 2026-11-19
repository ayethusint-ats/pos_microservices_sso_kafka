import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { isGatewayRequest } from '../auth/auth-token.interceptor';

/**
 * What the UI shows for a failed API call. Built from the RFC 9457 problem+json body that the
 * gateway and services return, so all errors look the same to the components.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail: string,
    readonly requestId: string | null,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(detail);
  }
}

/**
 * Central handling of API errors:
 *   401 → the session is gone (expired, logged out elsewhere): log in again, then come back
 *   403 → show the "forbidden" page
 *   any other status → ApiError that components display (including the requestId for support)
 */
export const apiErrorInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || !isGatewayRequest(request.url)) {
        return throwError(() => error);
      }
      if (error.status === 401 && !recentlyRedirectedToLogin()) {
        // Guard against a redirect loop: if the backend rejects even fresh tokens (e.g. wrong
        // audience configuration), Keycloak's SSO session would log us straight back in forever.
        sessionStorage.setItem(LAST_LOGIN_REDIRECT_KEY, String(Date.now()));
        auth.login(router.url);
      } else if (error.status === 403) {
        void router.navigate(['/forbidden']);
      }
      return throwError(() => toApiError(error));
    }),
  );
};

const LAST_LOGIN_REDIRECT_KEY = 'banking.lastLoginRedirectAt';
const LOGIN_LOOP_WINDOW_MS = 30_000;

function recentlyRedirectedToLogin(): boolean {
  const last = Number(sessionStorage.getItem(LAST_LOGIN_REDIRECT_KEY) ?? 0);
  return Date.now() - last < LOGIN_LOOP_WINDOW_MS;
}

export function toApiError(error: HttpErrorResponse): ApiError {
  const body = (error.error ?? {}) as Partial<{
    title: string;
    detail: string;
    requestId: string;
    errors: Record<string, string>;
  }>;
  if (error.status === 0) {
    // No HTTP response at all: network down, gateway not running, or blocked by CORS.
    return new ApiError(0, 'Network error', 'The server could not be reached.', null);
  }
  return new ApiError(
    error.status,
    body.title ?? error.statusText ?? 'Error',
    body.detail ?? error.message,
    body.requestId ?? error.headers?.get('X-Request-Id') ?? null,
    body.errors ?? {},
  );
}
