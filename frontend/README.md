# banking-frontend

Angular 22 SPA for the Banking Platform. Login with Keycloak (Authorization Code + PKCE)
via `angular-auth-oidc-client`; every API call goes through the API Gateway (`http://localhost:8080`).

```bash
npm install
npm start          # http://localhost:4200  (needs Keycloak + gateway + services running)
npm test           # unit tests (Vitest)
DEMO_USER_PASSWORD=... npm run e2e   # end-to-end login tests in your installed Chrome (Playwright)
```

Full explanation: [../documentation/07-angular-authentication.md](../documentation/07-angular-authentication.md)
