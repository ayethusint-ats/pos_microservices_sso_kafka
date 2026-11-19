import { Component, inject, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './auth/auth.service';

/** Application shell: navigation, who is logged in, login/logout. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  protected readonly auth = inject(AuthService);

  ngOnInit(): void {
    // The OIDC library already processed a possible login callback (app initializer),
    // so we can now send the user to the page they originally asked for.
    this.auth.restorePostLoginRedirect();
  }
}
