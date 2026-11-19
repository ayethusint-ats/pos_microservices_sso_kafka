import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ApiError, apiErrorInterceptor } from './api-error.interceptor';

describe('apiErrorInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: { login: ReturnType<typeof vi.fn> };
  let router: Router;

  beforeEach(() => {
    sessionStorage.clear();
    auth = { login: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  it('turns a problem+json body into an ApiError with the request id', () => {
    let received: ApiError | undefined;
    http.get('http://localhost:8080/api/accounts/1').subscribe({ error: (e) => (received = e) });

    backend.expectOne('http://localhost:8080/api/accounts/1').flush(
      { title: 'Business rule violation', detail: 'Insufficient funds', requestId: 'req-42' },
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    expect(received).toBeInstanceOf(ApiError);
    expect(received?.status).toBe(422);
    expect(received?.detail).toBe('Insufficient funds');
    expect(received?.requestId).toBe('req-42');
  });

  it('starts a new login on 401, but only once within the loop-protection window', () => {
    for (let i = 0; i < 2; i++) {
      http.get('http://localhost:8080/api/whoami').subscribe({ error: () => undefined });
      backend.expectOne('http://localhost:8080/api/whoami').flush({}, { status: 401, statusText: 'Unauthorized' });
    }
    expect(auth.login).toHaveBeenCalledTimes(1);
  });

  it('shows the forbidden page on 403', () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    http.get('http://localhost:8080/api/admin/users').subscribe({ error: () => undefined });
    backend.expectOne('http://localhost:8080/api/admin/users').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(navigate).toHaveBeenCalledWith(['/forbidden']);
  });
});
