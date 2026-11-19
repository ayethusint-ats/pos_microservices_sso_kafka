import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ApiError } from '../../core/api-error.interceptor';
import { Account, ApiService, Payment } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * Transfers between the customer's own accounts (Phase 9: token relay only allows own accounts;
 * transfers to other customers come with Phase 10).
 *
 * The form only offers the customer's own accounts, but that's convenience: payment-service
 * relays the token, and account-service refuses any account that isn't the caller's.
 */
@Component({
  selector: 'app-payments-page',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, FormsModule, ErrorMessage],
  templateUrl: './payments.page.html',
})
export class PaymentsPage implements OnInit {
  private readonly api = inject(ApiService);

  protected readonly accounts = signal<Account[]>([]);
  protected readonly payments = signal<Payment[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  /** Result of the last transfer that reached a status (COMPLETED or NEEDS_RECONCILIATION). */
  protected readonly lastPayment = signal<Payment | null>(null);

  protected readonly activeAccounts = computed(() => this.accounts().filter((a) => a.status === 'ACTIVE'));

  protected sourceAccountId = '';
  protected targetAccountId = '';
  protected amount: number | null = null;
  protected description = '';

  ngOnInit(): void {
    this.load();
  }

  protected accountLabel(id: string): string {
    const account = this.accounts().find((a) => a.id === id);
    return account ? `${account.accountNumber} (${account.type})` : `${id.substring(0, 8)}…`;
  }

  protected transfer(): void {
    if (!this.sourceAccountId || !this.targetAccountId || !this.amount) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.lastPayment.set(null);
    this.api
      .transfer({
        sourceAccountId: this.sourceAccountId,
        targetAccountId: this.targetAccountId,
        amount: this.amount,
        description: this.description,
      })
      .subscribe({
        next: (payment) => {
          this.busy.set(false);
          this.lastPayment.set(payment);
          this.amount = null;
          this.description = '';
          this.load();
        },
        error: (e: ApiError) => {
          // 422/503: the transfer did not happen, but it was recorded; reload the history.
          this.busy.set(false);
          this.error.set(e);
          this.load();
        },
      });
  }

  private load(): void {
    forkJoin({ accounts: this.api.myAccounts(), payments: this.api.myPayments() }).subscribe({
      next: ({ accounts, payments }) => {
        this.accounts.set(accounts);
        this.payments.set(payments.content);
        this.loading.set(false);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.loading.set(false);
      },
    });
  }
}
