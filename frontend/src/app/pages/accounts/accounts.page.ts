import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../auth/auth.service';
import { ApiError } from '../../core/api-error.interceptor';
import { Account, AccountType, ApiService, WhoAmI } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * Calls the API through the gateway: GET /api/whoami (user-service), GET/POST /api/accounts
 * (account-service). No code here deals with tokens: the interceptor attaches them.
 */
@Component({
  selector: 'app-accounts-page',
  imports: [CurrencyPipe, DatePipe, FormsModule, ErrorMessage],
  templateUrl: './accounts.page.html',
})
export class AccountsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  protected readonly whoAmI = signal<WhoAmI | null>(null);
  protected readonly accounts = signal<Account[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);

  protected newType: AccountType = 'SAVINGS';
  protected newCurrency = 'THB';
  protected readonly opening = signal(false);

  ngOnInit(): void {
    this.api.whoAmI().subscribe({
      next: (me) => this.whoAmI.set(me),
      error: (e: ApiError) => this.error.set(e),
    });
    this.loadAccounts();
  }

  protected openAccount(): void {
    const ownerId = this.auth.userId();
    if (!ownerId) {
      return;
    }
    this.opening.set(true);
    this.error.set(null);
    this.api.openAccount(ownerId, this.newType, this.newCurrency).subscribe({
      next: () => {
        this.opening.set(false);
        this.loadAccounts();
      },
      error: (e: ApiError) => {
        this.opening.set(false);
        this.error.set(e);
      },
    });
  }

  private loadAccounts(): void {
    const ownerId = this.auth.userId();
    if (!ownerId) {
      return;
    }
    this.loading.set(true);
    this.api.accountsOf(ownerId).subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loading.set(false);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.loading.set(false);
      },
    });
  }
}
