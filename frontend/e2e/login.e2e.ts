import { expect, Page, Request, test } from '@playwright/test';

const PASSWORD = process.env['DEMO_USER_PASSWORD'] ?? '';
const GATEWAY = 'http://localhost:8080';
const KEYCLOAK = 'http://localhost:8180';

test.beforeAll(() => {
  if (!PASSWORD) {
    throw new Error('Set DEMO_USER_PASSWORD (from ../.env) before running the e2e tests');
  }
});

/** Fills in the REAL Keycloak login form (the app never sees these fields). */
async function signInAtKeycloak(page: Page, username: string): Promise<void> {
  await expect(page).toHaveURL(new RegExp(`^${KEYCLOAK}/realms/banking/protocol/openid-connect/auth`));
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();
}

test('customer logs in with Authorization Code + PKCE and calls the API through the gateway', async ({ page }) => {
  const requests: Request[] = [];
  page.on('request', (r) => requests.push(r));

  await page.goto('/');
  await page.getByTestId('login-cta').click();

  // 1. The browser is redirected to Keycloak with a PKCE challenge.
  await expect(page).toHaveURL(/code_challenge_method=S256/);
  await expect(page).toHaveURL(/code_challenge=/);
  await signInAtKeycloak(page, 'alice');

  // 2. Back in the app, already on the page that was requested, with the user shown.
  await expect(page).toHaveURL('http://localhost:4200/accounts');
  await expect(page.getByTestId('current-user')).toHaveText('alice');
  await expect(page.getByText('CUSTOMER', { exact: true })).toBeVisible();

  // 3. The code was exchanged at the token endpoint WITH the verifier, and removed from the URL.
  const tokenRequest = requests.find((r) => r.url().endsWith('/protocol/openid-connect/token'));
  expect(tokenRequest?.postData()).toContain('grant_type=authorization_code');
  expect(tokenRequest?.postData()).toContain('code_verifier=');
  expect(page.url()).not.toContain('code=');

  // 4. API calls go to the gateway with a bearer token, and carry no user ID: the server takes it
  //    from the token (Phase 8).
  await expect(page.getByRole('heading', { name: 'My accounts' })).toBeVisible();
  const apiCall = requests.find((r) => r.url().startsWith(`${GATEWAY}/api/accounts`));
  expect(apiCall?.headers()['authorization']).toMatch(/^Bearer ey/);
  expect(apiCall?.url()).not.toContain('ownerId');

  // 5. Keycloak never receives the access token as a bearer header.
  for (const r of requests.filter((x) => x.url().startsWith(KEYCLOAK))) {
    expect(r.headers()['authorization'] ?? '').not.toMatch(/^Bearer/);
  }

  // 6. A write call (POST, with CORS preflight) works end to end.
  const rows = page.locator('[data-testid="accounts-table"] tbody tr');
  // Wait until the list has finished loading (table or "no accounts"), THEN count.
  await expect(page.locator('[data-testid="accounts-table"], [data-testid="no-accounts"]').first()).toBeVisible();
  const before = (await page.getByTestId('no-accounts').count()) > 0 ? 0 : await rows.count();
  await page.getByTestId('open-account').click();
  await expect(rows).toHaveCount(before + 1);
});

test('route guard: a customer cannot open the back office', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('login').click();
  await signInAtKeycloak(page, 'alice');
  await expect(page.getByTestId('current-user')).toHaveText('alice');

  await expect(page.getByRole('link', { name: 'Back office' })).toHaveCount(0);
  await page.goto('/backoffice');
  await expect(page.getByTestId('forbidden')).toBeVisible();
});

test('deep link: login returns to the originally requested page', async ({ page }) => {
  await page.goto('/token');
  await signInAtKeycloak(page, 'bob');

  await expect(page).toHaveURL('http://localhost:4200/token');
  await expect(page.getByTestId('access-claims')).toContainText('"preferred_username": "bob"');
});

test('token refresh uses the refresh token grant', async ({ page }) => {
  await page.goto('/token');
  await signInAtKeycloak(page, 'bob');
  await expect(page.getByTestId('refresh')).toBeVisible();

  const refresh = page.waitForRequest(
    (r) => r.url().endsWith('/protocol/openid-connect/token') && (r.postData() ?? '').includes('grant_type=refresh_token'),
  );
  await page.getByTestId('refresh').click();
  await refresh;
  await expect(page.getByText('Last manual refresh')).toBeVisible();
});

test('admin lands in the back office after login (deep link) and has no customer menu', async ({ page }) => {
  await page.goto('/backoffice');
  await signInAtKeycloak(page, 'ada');

  await expect(page).toHaveURL('http://localhost:4200/backoffice');
  await expect(page.getByText('Administrator: you can view customers')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Accounts' })).toHaveCount(0);
});

test('logout ends the Keycloak SSO session, so the next visit asks for the password again', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('login').click();
  await signInAtKeycloak(page, 'alice');
  await expect(page.getByTestId('current-user')).toHaveText('alice');

  await page.getByTestId('logout').click();
  await expect(page).toHaveURL('http://localhost:4200/');
  await expect(page.getByTestId('login')).toBeVisible();

  // If only the local tokens had been deleted, Keycloak's SSO cookie would log us straight back in.
  await page.goto('/accounts');
  await expect(page.locator('#username')).toBeVisible();
});
