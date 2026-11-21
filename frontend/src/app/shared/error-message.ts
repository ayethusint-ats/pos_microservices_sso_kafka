import { Component, input } from '@angular/core';
import { ApiError } from '../core/api-error.interceptor';

/**
 * Shows an ApiError, including the IDs support needs: the request ID finds the log lines, the
 * trace ID (Phase 14) opens the whole request in Jaeger.
 */
@Component({
  selector: 'app-error-message',
  template: `
    @if (error(); as e) {
      <div class="error" role="alert" data-testid="error">
        <strong>{{ e.title }}</strong> ({{ e.status }}): {{ e.detail }}
        @if (e.code) {
          <code data-testid="error-code">{{ e.code }}</code>
        }
        @for (field of fieldNames(e); track field) {
          <div>{{ field }}: {{ e.fieldErrors[field] }}</div>
        }
        @if (e.requestId) {
          <div><small>Request ID: <code>{{ e.requestId }}</code></small></div>
        }
        @if (e.traceId) {
          <div><small>Trace ID: <code data-testid="trace-id">{{ e.traceId }}</code></small></div>
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
