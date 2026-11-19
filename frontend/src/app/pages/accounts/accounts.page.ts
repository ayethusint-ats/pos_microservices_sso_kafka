import { CurrencyPipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiError } from '../../core/api-error.interceptor';
import { Account, AccountType, ApiService } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * The customer's own accounts. No user ID appears anywhere in these calls: the backend
 * identifies the owner from the token, and answers 404 for accounts that aren't theirs.
 */
@Component({
  selector: 'app-accounts-page',
  imports: [CurrencyPipe, FormsModule, ErrorMessage],
  templateUrl: './accounts.page.html',
})
export class AccountsPage implements OnInit {
  private readonly api = inject(ApiService);

  protected readonly accounts = signal<Account[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected newType: AccountType = 'SAVINGS';
  protected newCurrency = 'THB';
  /** Amount typed per account row, keyed by account id. */
  protected amounts: Record<string, number | null> = {};

  ngOnInit(): void {
    this.load();
  }

  protected openAccount(): void {
    this.run(this.api.openAccount(this.newType, this.newCurrency));
  }

  protected deposit(account: Account): void {
    const amount = this.amounts[account.id];
    if (amount) {
      this.run(this.api.deposit(account.id, amount));
    }
  }

  protected withdraw(account: Account): void {
    const amount = this.amounts[account.id];
    if (amount) {
      this.run(this.api.withdraw(account.id, amount));
    }
  }

  private run(call: Observable<unknown>): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.amounts = {};
        this.load();
      },
      error: (e: ApiError) => {
        this.busy.set(false);
        this.error.set(e);
      },
    });
  }

  private load(): void {
    this.api.myAccounts().subscribe({
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
