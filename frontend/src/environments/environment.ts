/**
 * Public configuration only. Everything here ships to the browser and anyone can read it.
 * That is fine: a public OIDC client has no secret. Security comes from PKCE, exact redirect
 * URIs registered in Keycloak, and the backend validating every token.
 */
export const environment = {
  /** The API Gateway. The ONLY origin that receives access tokens (see auth-token.interceptor.ts). */
  apiUrl: 'http://localhost:8080',
  keycloak: {
    authority: 'http://localhost:8180/realms/banking',
    clientId: 'banking-frontend',
  },
};
