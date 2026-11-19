import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ApiError } from '../../core/api-error.interceptor';
import { Account, ApiService, Payment } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * Transfers from one of the customer's accounts to ANY account number: their own, or another
 * customer's (Phase 10: payment-service credits the recipient with its own Client Credentials token).
 *
 * The "From" list only offers the customer's own accounts, but that's convenience: the debit
 * uses the customer's token, and account-service refuses any source account that isn't theirs.
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
  /** For the history: "own account" vs "someone else's". */
  protected readonly ownNumbers = computed(() => new Set(this.accounts().map((a) => a.accountNumber)));

  protected sourceAccountId = '';
  protected targetAccountNumber = '';
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
    if (!this.sourceAccountId || !this.targetAccountNumber || !this.amount) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.lastPayment.set(null);
    this.api
      .transfer({
        sourceAccountId: this.sourceAccountId,
        targetAccountNumber: this.targetAccountNumber.trim(),
        amount: this.amount,
        description: this.description,
      })
      .subscribe({
        next: (payment) => {
          this.busy.set(false);
          this.lastPayment.set(payment);
          this.amount = null;
          this.targetAccountNumber = '';
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
