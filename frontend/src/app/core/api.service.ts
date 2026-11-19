import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface WhoAmI {
  subject: string;
  username: string;
  clientId: string;
  audience: string[];
  authorities: string[];
  issuedAt: string;
  expiresAt: string;
}

export type AccountType = 'CHECKING' | 'SAVINGS';

export interface Account {
  id: string;
  accountNumber: string;
  ownerId: string;
  type: AccountType;
  currency: string;
  balance: number;
  status: 'ACTIVE' | 'FROZEN' | 'CLOSED';
  createdAt: string;
}

/**
 * All calls go to the API Gateway. Angular never talks to a service directly.
 * The token is added by authTokenInterceptor; errors are normalized by apiErrorInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  whoAmI(): Observable<WhoAmI> {
    return this.http.get<WhoAmI>(`${this.base}/api/whoami`);
  }

  /** Phase 7: ownerId is still sent by the client. Phase 8 takes it from the token on the server. */
  accountsOf(ownerId: string): Observable<Account[]> {
    return this.http.get<Account[]>(`${this.base}/api/accounts`, { params: { ownerId } });
  }

  openAccount(ownerId: string, type: AccountType, currency: string): Observable<Account> {
    return this.http.post<Account>(`${this.base}/api/accounts`, { ownerId, type, currency });
  }
}
