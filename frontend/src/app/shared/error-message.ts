import { Component, input } from '@angular/core';
import { ApiError } from '../core/api-error.interceptor';

/** Shows an ApiError, including the request ID that support can look up in the logs. */
@Component({
  selector: 'app-error-message',
  template: `
    @if (error(); as e) {
      <div class="error" role="alert" data-testid="error">
        <strong>{{ e.title }}</strong> ({{ e.status }}): {{ e.detail }}
        @for (field of fieldNames(e); track field) {
          <div>{{ field }}: {{ e.fieldErrors[field] }}</div>
        }
        @if (e.requestId) {
          <div><small>Request ID: <code>{{ e.requestId }}</code></small></div>
        }
      </div>
    }
  `,
})
export class ErrorMessage {
  readonly error = input<ApiError | null>(null);

  protected fieldNames(error: ApiError): string[] {
    return Object.keys(error.fieldErrors);
  }
}
