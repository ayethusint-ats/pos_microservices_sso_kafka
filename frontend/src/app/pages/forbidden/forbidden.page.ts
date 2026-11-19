import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-forbidden-page',
  imports: [RouterLink],
  template: `
    <h1>Not allowed</h1>
    <div class="card" data-testid="forbidden">
      <p>Your account doesn't have permission to open this page.</p>
      <a routerLink="/">Back to the start page</a>
    </div>
  `,
})
export class ForbiddenPage {}
