import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../auth/auth.service';

@Component({
  selector: 'app-home-page',
  imports: [RouterLink],
  template: `
    <h1>Welcome to Modern Bank</h1>
    @if (auth.isAuthenticated()) {
      <p data-testid="welcome">Hello <strong>{{ auth.username() }}</strong>, you are signed in.</p>
      @if (auth.hasRole('CUSTOMER')) {
        <p><a routerLink="/accounts">Go to your accounts →</a></p>
      }
      @if (auth.isStaff()) {
        <p><a routerLink="/backoffice">Open the back office →</a></p>
      }
    } @else {
      <div class="card">
        <p>
          Signing in takes you to the bank's central login page (Keycloak). Your password is entered
          there, never in this app. Afterwards you're sent back here, already signed in.
        </p>
        <button type="button" (click)="auth.login('/accounts')" data-testid="login-cta">Log in</button>
      </div>
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);
}
