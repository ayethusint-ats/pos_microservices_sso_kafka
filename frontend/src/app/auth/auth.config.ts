import { LogLevel, PassedInitialConfig } from 'angular-auth-oidc-client';
import { environment } from '../../environments/environment';

/**
 * OpenID Connect settings for angular-auth-oidc-client, a certified OIDC library.
 * We don't write PKCE, state/nonce handling, token validation or refresh logic ourselves.
 */
export const authConfig: PassedInitialConfig = {
  config: {
    // Where Keycloak lives. The library loads /.well-known/openid-configuration from here
    // and learns every endpoint (auth, token, logout, JWKS) from it.
    authority: environment.keycloak.authority,
    clientId: environment.keycloak.clientId,

    // Authorization Code flow. With a public client the library always uses PKCE (S256).
    responseType: 'code',
    scope: 'openid profile email',

    // Must match the client's redirectUris / post.logout.redirect.uris in the Keycloak realm.
    redirectUrl: `${window.location.origin}/`,
    postLogoutRedirectUri: `${window.location.origin}/`,

    // Token management: refresh 30 s before the 5-minute access token expires, using the
    // refresh token. Keycloak rotates refresh tokens, so every refresh returns a new one.
    silentRenew: true,
    useRefreshToken: true,
    renewTimeBeforeTokenExpiresInSeconds: 30,
    // Keycloak doesn't repeat the nonce in ID tokens obtained by refresh. The nonce protects the
    // initial login (replay), not refreshes.
    ignoreNonceAfterRefresh: true,
    // We deliberately do NOT request "offline_access": Keycloak offline tokens survive logout and
    // the end of the SSO session, which is wrong for a banking app. Keycloak still issues normal
    // refresh tokens bound to the SSO session, so the library's warning doesn't apply here.
    disableRefreshTokenOfflineAccessScopeWarning: true,

    // Profile claims come from the ID token. No extra call to /userinfo.
    autoUserInfo: false,

    logLevel: LogLevel.Warn,
  },
};
