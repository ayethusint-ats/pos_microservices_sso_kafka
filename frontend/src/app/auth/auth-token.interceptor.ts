import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { switchMap, take } from 'rxjs';
import { environment } from '../../environments/environment';

/**
 * Adds "Authorization: Bearer <access token>" to requests for OUR API Gateway, and to nothing else.
 *
 * <p>The allow-list matters: an interceptor that attached the token to every request would leak
 * it to any third-party URL the app ever calls (CDNs, analytics, a malicious link). Whoever
 * holds a bearer token can use it.
 */
export const authTokenInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isGatewayRequest(request.url)) {
    return next(request);
  }

  const oidc = inject(OidcSecurityService);
  return oidc.getAccessToken().pipe(
    take(1),
    switchMap((token) =>
      next(token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request),
    ),
  );
};

export function isGatewayRequest(url: string): boolean {
  return url.startsWith(`${environment.apiUrl}/`);
}
