import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { of } from 'rxjs';
import { authTokenInterceptor } from './auth-token.interceptor';

describe('authTokenInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authTokenInterceptor])),
        provideHttpClientTesting(),
        { provide: OidcSecurityService, useValue: { getAccessToken: () => of('access-token-123') } },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('adds the bearer token to API gateway requests', () => {
    http.get('http://localhost:8080/api/accounts').subscribe();

    const request = backend.expectOne('http://localhost:8080/api/accounts');
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-token-123');
    request.flush([]);
  });

  it('never sends the token to other origins', () => {
    http.get('https://cdn.example.com/data.json').subscribe();
    http.get('http://localhost:8180/realms/banking/.well-known/openid-configuration').subscribe();
    // Prefix trick: same host name but a different origin must not match.
    http.get('http://localhost:8080.evil.example/api/steal').subscribe();

    for (const url of [
      'https://cdn.example.com/data.json',
      'http://localhost:8180/realms/banking/.well-known/openid-configuration',
      'http://localhost:8080.evil.example/api/steal',
    ]) {
      const request = backend.expectOne(url);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({});
    }
  });
});
