import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ApiError } from '../../core/api-error.interceptor';
import { ApiService, Notification, Transaction } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * The customer's statement (transaction-service) and e-mails (notification-service).
 *
 * Both are read models fed by Kafka, so they are EVENTUALLY consistent: right after a transfer
 * they may lag by a moment. That's why there is a Refresh button rather than a promise that
 * everything is already here.
 */
@Component({
  selector: 'app-activity-page',
  imports: [CurrencyPipe, DatePipe, ErrorMessage],
  templateUrl: './activity.page.html',
})
export class ActivityPage implements OnInit {
  private readonly api = inject(ApiService);

  protected readonly transactions = signal<Transaction[]>([]);
  protected readonly notifications = signal<Notification[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    this.refresh();
  }

  protected refresh(): void {
    this.error.set(null);
    forkJoin({ transactions: this.api.myTransactions(), notifications: this.api.myNotifications() }).subscribe({
      next: ({ transactions, notifications }) => {
        this.transactions.set(transactions.content);
        this.notifications.set(notifications);
        this.loading.set(false);
      },
      error: (e: ApiError) => {
        this.error.set(e);
        this.loading.set(false);
      },
    });
  }

  protected label(t: Transaction): string {
    switch (t.kind) {
      case 'DEPOSIT':
        return 'Deposit';
      case 'WITHDRAWAL':
        return 'Withdrawal';
      case 'TRANSFER_OUT':
        return 'Transfer sent';
      case 'TRANSFER_IN':
        return 'Transfer received';
      case 'REFUND':
        return 'Refund';
    }
  }
}
