import { DatePipe, JsonPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { AuthService } from '../../auth/auth.service';

/**
 * Learning tool: shows what is inside the tokens and how token renewal works.
 * Decoding a JWT in the browser is fine for DISPLAY. The browser never validates tokens for
 * security purposes; that's the backend's job.
 */
@Component({
  selector: 'app-token-page',
  imports: [DatePipe, JsonPipe],
  template: `
    <h1>Token inspector</h1>

    @if (auth.accessTokenClaims(); as access) {
      <div class="card">
        <p>
          Access token expires in <strong data-testid="expires-in">{{ secondsLeft() }}</strong> s
          ({{ access.exp * 1000 | date: 'mediumTime' }}). The library renews it automatically
          30 s before expiry using the refresh token.
        </p>
        <button type="button" (click)="refresh()" [disabled]="refreshing()" data-testid="refresh">
          Refresh tokens now
        </button>
        @if (lastRefresh()) {
          <span class="muted"> Last manual refresh: {{ lastRefresh() | date: 'mediumTime' }}</span>
        }
      </div>

      <h2>Access token (sent to the API)</h2>
      <p class="muted">Audience, roles and scopes: what the gateway and services use for decisions.</p>
      <pre data-testid="access-claims">{{ access | json }}</pre>
    }

    @if (auth.idTokenClaims(); as id) {
      <h2>ID token (for this app only, never sent to the API)</h2>
      <pre>{{ id | json }}</pre>
    }
  `,
})
export class TokenPage implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly now = signal(Date.now());
  protected readonly refreshing = signal(false);
  protected readonly lastRefresh = signal<number | null>(null);

  protected readonly secondsLeft = computed(() => {
    const exp = this.auth.accessTokenClaims()?.exp;
    return exp ? Math.max(0, Math.round(exp - this.now() / 1000)) : 0;
  });

  ngOnInit(): void {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected refresh(): void {
    this.refreshing.set(true);
    this.auth.refreshNow().subscribe({
      next: () => {
        this.refreshing.set(false);
        this.lastRefresh.set(Date.now());
      },
      error: () => this.refreshing.set(false),
    });
  }
}
