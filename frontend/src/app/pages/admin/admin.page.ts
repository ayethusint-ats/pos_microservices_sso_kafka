import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ApiError } from '../../core/api-error.interceptor';
import { ApiService, WhoAmI } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * ADMIN area (placeholder until Phase 8 adds admin APIs under /api/admin/**).
 *
 * roleGuard('ADMIN') only hides this page in the UI. What actually counts is what the BACKEND
 * concludes from the token, so this page shows the authorities user-service derived after
 * validating it.
 *
 * Note: the page deliberately does NOT call /actuator/**. Actuator is an operations interface
 * (Prometheus, kubectl, ops tools), not a product API, and the gateway allows no CORS for it.
 */
@Component({
  selector: 'app-admin-page',
  imports: [ErrorMessage],
  template: `
    <h1>Administration</h1>
    <app-error-message [error]="error()" />
    <div class="card" data-testid="admin-authorities">
      <p class="muted">Authorities the backend derived from your token:</p>
      @for (authority of roleAuthorities(); track authority) {
        <span class="chip">{{ authority }}</span>&nbsp;
      }
      <p class="muted">Admin functions (manage users and accounts) arrive in Phase 8.</p>
    </div>
  `,
})
export class AdminPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly whoAmI = signal<WhoAmI | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly roleAuthorities = computed(() =>
    (this.whoAmI()?.authorities ?? []).filter((a) => /^ROLE_[A-Z]+$/.test(a)),
  );

  ngOnInit(): void {
    this.api.whoAmI().subscribe({
      next: (me) => this.whoAmI.set(me),
      error: (e: ApiError) => this.error.set(e),
    });
  }
}
