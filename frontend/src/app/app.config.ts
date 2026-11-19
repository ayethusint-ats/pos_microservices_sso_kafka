import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideAuth, withAppInitializerAuthCheck } from 'angular-auth-oidc-client';
import { routes } from './app.routes';
import { authConfig } from './auth/auth.config';
import { authTokenInterceptor } from './auth/auth-token.interceptor';
import { apiErrorInterceptor } from './core/api-error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),

    // withAppInitializerAuthCheck: before the first route renders, the library
    //   - on a login callback (?code=...&state=...): checks state, exchanges code + PKCE verifier
    //     for tokens, validates the ID token, and removes the code from the URL
    //   - otherwise: restores an existing session from storage
    // So guards always see the real authentication state.
    provideAuth(authConfig, withAppInitializerAuthCheck()),

    // Interceptors run in order for requests: first attach the token, then handle errors.
    provideHttpClient(withInterceptors([authTokenInterceptor, apiErrorInterceptor])),
  ],
};
