import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests: a real browser, the real Keycloak login page, the real gateway and services.
 *
 * Prerequisites (see documentation/07-angular-authentication.md):
 *   docker compose up -d                      # postgres + keycloak
 *   user-service, account-service, api-gateway running
 *   DEMO_USER_PASSWORD exported (from ../.env)
 *
 * Uses the installed Google Chrome (channel: 'chrome'), so no browser download is needed.
 * The Angular dev server is started automatically.
 */
export default defineConfig({
  testDir: './e2e',
  // *.e2e.ts (not *.spec.ts) so the Vitest unit-test run never picks these up.
  testMatch: '**/*.e2e.ts',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4200',
    channel: 'chrome',
    headless: true,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npx ng serve --port 4200',
    url: 'http://localhost:4200',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
