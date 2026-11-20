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

export interface Address {
  street: string;
  city: string;
  postalCode: string;
  country: string;
}

export interface CustomerProfile {
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string | null;
  address: Address;
}

export interface Customer extends CustomerProfile {
  id: string;
  keycloakUserId: string;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type AccountType = 'CHECKING' | 'SAVINGS';
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';

export interface Account {
  id: string;
  accountNumber: string;
  ownerId: string;
  type: AccountType;
  currency: string;
  balance: number;
  status: AccountStatus;
  createdAt: string;
}

export type PaymentStatus =
  | 'PENDING'
  | 'DEBITED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'FAILED'
  | 'REVERSED'
  | 'NEEDS_RECONCILIATION';

export interface TransferRequest {
  sourceAccountId: string;
  /** Any customer's 10-digit account number (Phase 10). */
  targetAccountNumber: string;
  amount: number;
  description: string;
}

export interface Payment extends Omit<TransferRequest, 'targetAccountNumber'> {
  id: string;
  targetAccountId: string | null;
  /** null for Phase 9 payments, which were addressed by account ID. */
  targetAccountNumber: string | null;
  payerId: string;
  currency: string | null;
  status: PaymentStatus;
  failureCode: string | null;
  failureReason: string | null;
  createdAt: string;
}

export type TransactionKind = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER_OUT' | 'TRANSFER_IN' | 'REFUND';

/** A statement line from transaction-service (built from account-service's Kafka events). */
export interface Transaction {
  id: string;
  accountId: string;
  accountNumber: string;
  kind: TransactionKind;
  direction: 'CREDIT' | 'DEBIT';
  amount: number;
  currency: string;
  balanceAfter: number;
  paymentId: string | null;
  occurredAt: string;
}

/** A simulated e-mail from notification-service. */
export interface Notification {
  id: string;
  to: string | null;
  subject: string;
  body: string;
  createdAt: string;
}

/**
 * All calls go to the API Gateway. The token is added by authTokenInterceptor, errors are
 * normalized by apiErrorInterceptor.
 *
 * Notice what is NOT here since Phase 8: no ownerId on self-service calls. The server knows who
 * the caller is from the token, so the client can't ask for someone else's data.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  whoAmI(): Observable<WhoAmI> {
    return this.http.get<WhoAmI>(`${this.base}/api/whoami`);
  }

  // ------------------------------------------------------------ customer self-service

  myProfile(): Observable<Customer> {
    return this.http.get<Customer>(`${this.base}/api/customers/me`);
  }

  createMyProfile(profile: CustomerProfile): Observable<Customer> {
    return this.http.post<Customer>(`${this.base}/api/customers/me`, profile);
  }

  updateMyProfile(profile: CustomerProfile): Observable<Customer> {
    return this.http.put<Customer>(`${this.base}/api/customers/me`, profile);
  }

  myAccounts(): Observable<Account[]> {
    return this.http.get<Account[]>(`${this.base}/api/accounts`);
  }

  openAccount(type: AccountType, currency: string): Observable<Account> {
    return this.http.post<Account>(`${this.base}/api/accounts`, { type, currency });
  }

  deposit(accountId: string, amount: number): Observable<Account> {
    return this.http.post<Account>(`${this.base}/api/accounts/${accountId}/deposit`, { amount });
  }

  withdraw(accountId: string, amount: number): Observable<Account> {
    return this.http.post<Account>(`${this.base}/api/accounts/${accountId}/withdraw`, { amount });
  }

  /**
   * 201 → COMPLETED, 202 → NEEDS_RECONCILIATION (both arrive here as a Payment).
   * 422/503 → ApiError: the transfer did not happen.
   */
  transfer(request: TransferRequest): Observable<Payment> {
    return this.http.post<Payment>(`${this.base}/api/payments`, request);
  }

  myPayments(page = 0, size = 20): Observable<Page<Payment>> {
    return this.http.get<Page<Payment>>(`${this.base}/api/payments`, { params: { page, size } });
  }

  /** Eventually consistent: a transfer appears here shortly AFTER it completed (Kafka → transaction-service). */
  myTransactions(accountId?: string, page = 0, size = 50): Observable<Page<Transaction>> {
    const params: Record<string, string | number> = { page, size };
    if (accountId) {
      params['accountId'] = accountId;
    }
    return this.http.get<Page<Transaction>>(`${this.base}/api/transactions`, { params });
  }

  myNotifications(): Observable<Notification[]> {
    return this.http.get<Notification[]>(`${this.base}/api/notifications`);
  }

  // ------------------------------------------------------------ back office (SUPPORT, ADMIN)

  customers(page = 0, size = 20): Observable<Page<Customer>> {
    return this.http.get<Page<Customer>>(`${this.base}/api/admin/customers`, {
      params: { page, size, sort: 'lastName,asc' },
    });
  }

  accountsOfCustomer(keycloakUserId: string): Observable<Account[]> {
    return this.http.get<Account[]>(`${this.base}/api/admin/accounts`, { params: { ownerId: keycloakUserId } });
  }

  /** ADMIN only. SUPPORT gets 403 from the backend even if the UI showed the button. */
  changeAccountStatus(accountId: string, status: AccountStatus, reason: string): Observable<Account> {
    return this.http.patch<Account>(`${this.base}/api/admin/accounts/${accountId}/status`, { status, reason });
  }
}
