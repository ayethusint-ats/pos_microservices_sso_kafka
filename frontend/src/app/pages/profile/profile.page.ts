import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../auth/auth.service';
import { ApiError } from '../../core/api-error.interceptor';
import { ApiService, Customer, CustomerProfile } from '../../core/api.service';
import { ErrorMessage } from '../../shared/error-message';

/**
 * GET /api/customers/me → show and edit the profile.
 * 404 → the customer has a login but no profile yet → registration form (POST /me).
 */
@Component({
  selector: 'app-profile-page',
  imports: [FormsModule, ErrorMessage],
  templateUrl: './profile.page.html',
})
export class ProfilePage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  protected readonly existing = signal<Customer | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected form: CustomerProfile = emptyProfile();

  ngOnInit(): void {
    this.api.myProfile().subscribe({
      next: (customer) => this.show(customer),
      error: (e: ApiError) => {
        this.loading.set(false);
        if (e.status === 404) {
          this.prefillFromIdToken();     // not an error: no profile yet
        } else {
          this.error.set(e);
        }
      },
    });
  }

  protected save(): void {
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);
    const call = this.existing() ? this.api.updateMyProfile(this.form) : this.api.createMyProfile(this.form);
    call.subscribe({
      next: (customer) => {
        this.saving.set(false);
        this.saved.set(true);
        this.show(customer);
      },
      error: (e: ApiError) => {
        this.saving.set(false);
        this.error.set(e);
      },
    });
  }

  private show(customer: Customer): void {
    this.existing.set(customer);
    this.loading.set(false);
    this.form = {
      firstName: customer.firstName,
      lastName: customer.lastName,
      email: customer.email,
      phoneNumber: customer.phoneNumber,
      address: { ...customer.address },
    };
  }

  /** Convenience: Keycloak already knows the name and email. */
  private prefillFromIdToken(): void {
    const claims = this.auth.idTokenClaims() ?? {};
    this.form = {
      ...emptyProfile(),
      firstName: String(claims['given_name'] ?? ''),
      lastName: String(claims['family_name'] ?? ''),
      email: String(claims['email'] ?? ''),
    };
  }
}

function emptyProfile(): CustomerProfile {
  return {
    firstName: '',
    lastName: '',
    email: '',
    phoneNumber: null,
    address: { street: '', city: '', postalCode: '', country: 'TH' },
  };
}
