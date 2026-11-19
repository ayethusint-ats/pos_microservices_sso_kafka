import { CurrencyPipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { AuthService } from '../../auth/auth.service';
import { ApiError } from '../../core/api-error.interceptor';
import { Account, AccountStatus, ApiService, Customer } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * Back office for staff.
 *   SUPPORT: view customers and their accounts (to help callers). Read only.
 *   ADMIN:   additionally freeze / unfreeze accounts.
 * Nobody here can deposit, withdraw or transfer.
 *
 * The freeze buttons are hidden for SUPPORT as a convenience only. If a SUPPORT user called the
 * API directly, account-service would answer 403 (@AdminOnly).
 */
@Component({
  selector: 'app-backoffice-page',
  imports: [CurrencyPipe, ErrorMessage],
  templateUrl: './backoffice.page.html',
})
export class BackofficePage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthService);

  protected readonly customers = signal<Customer[]>([]);
  protected readonly selected = signal<Customer | null>(null);
  protected readonly accounts = signal<Account[]>([]);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly busy = signal(false);

  ngOnInit(): void {
    this.api.customers().subscribe({
      next: (page) => this.customers.set(page.content),
      error: (e: ApiError) => this.error.set(e),
    });
  }

  protected select(customer: Customer): void {
    this.selected.set(customer);
    this.loadAccounts(customer);
  }

  protected setStatus(account: Account, status: AccountStatus): void {
    const customer = this.selected();
    if (!customer) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.changeAccountStatus(account.id, status, `Changed in back office by ${this.auth.username()}`).subscribe({
      next: () => {
        this.busy.set(false);
        this.loadAccounts(customer);
      },
      error: (e: ApiError) => {
        this.busy.set(false);
        this.error.set(e);
      },
    });
  }

  private loadAccounts(customer: Customer): void {
    this.api.accountsOfCustomer(customer.keycloakUserId).subscribe({
      next: (accounts) => this.accounts.set(accounts),
      error: (e: ApiError) => this.error.set(e),
    });
  }
}
