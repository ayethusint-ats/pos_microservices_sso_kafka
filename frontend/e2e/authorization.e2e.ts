import { Browser, expect, Page, test } from '@playwright/test';

const PASSWORD = process.env['DEMO_USER_PASSWORD'] ?? '';

/** Each user gets an isolated browser context: separate cookies and storage, like separate people. */
async function signedIn(browser: Browser, username: string, path: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(path);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('current-user')).toHaveText(username);
  return page;
}

/** Alice has a profile (creates it on the first run, reuses it afterwards: the test is re-runnable). */
async function ensureProfile(page: Page): Promise<void> {
  await page.goto('/profile');
  await expect(page.getByTestId('profile-form')).toBeVisible();
  if (await page.getByTestId('no-profile').isVisible()) {
    await expect(page.getByTestId('email')).toHaveValue('alice@example.com');   // pre-filled from ID token
    await page.getByTestId('street').fill('1 Silom Rd');
    await page.getByTestId('city').fill('Bangkok');
    await page.getByTestId('postal-code').fill('10500');
    await page.getByTestId('save-profile').click();
    await expect(page.getByTestId('saved')).toBeVisible();
  }
}

test('customer, support and admin each get exactly their permissions on the same account', async ({ browser }) => {
  test.skip(!PASSWORD, 'Set DEMO_USER_PASSWORD (from ../.env)');

  // --- alice (CUSTOMER): profile, new account, deposit -----------------------------------------
  const alice = await signedIn(browser, 'alice', '/accounts');
  await ensureProfile(alice);
  await alice.goto('/accounts');
  await expect(alice.getByRole('heading', { name: 'My accounts' })).toBeVisible();
  const rows = alice.locator('[data-testid="accounts-table"] tbody tr');
  // Wait until the list has finished loading (table or "no accounts"), THEN count.
  await expect(alice.locator('[data-testid="accounts-table"], [data-testid="no-accounts"]').first()).toBeVisible();
  const before = (await alice.getByTestId('no-accounts').count()) > 0 ? 0 : await rows.count();
  await alice.getByTestId('open-account').click();
  await expect(rows).toHaveCount(before + 1);

  const accountRow = rows.last();
  const accountNumber = await accountRow.getAttribute('data-account');
  await accountRow.getByTestId('amount').fill('100');
  await accountRow.getByTestId('deposit').click();
  await expect(alice.locator(`tr[data-account="${accountNumber}"] [data-testid="balance"]`)).toContainText('100.00');

  // --- sam (SUPPORT): can see alice and her account, but has no freeze button ------------------
  const sam = await signedIn(browser, 'sam', '/backoffice');
  await expect(sam.getByText('Support: read-only access')).toBeVisible();
  await sam.locator('tr[data-email="alice@example.com"]').getByTestId('view-accounts').click();
  const samRow = sam.locator(`[data-testid="customer-accounts"] tr[data-account="${accountNumber}"]`);
  await expect(samRow).toBeVisible();
  await expect(sam.getByTestId('freeze')).toHaveCount(0);

  // --- ada (ADMIN): freezes the account --------------------------------------------------------
  const ada = await signedIn(browser, 'ada', '/backoffice');
  await ada.locator('tr[data-email="alice@example.com"]').getByTestId('view-accounts').click();
  const adaRow = ada.locator(`[data-testid="customer-accounts"] tr[data-account="${accountNumber}"]`);
  await adaRow.getByTestId('freeze').click();
  await expect(adaRow.getByTestId('status')).toHaveText('FROZEN');

  // --- alice: the backend refuses to move money from a frozen account --------------------------
  await alice.reload();
  const aliceRow = alice.locator(`tr[data-account="${accountNumber}"]`);
  await expect(aliceRow.getByTestId('status')).toHaveText('FROZEN');
  await aliceRow.getByTestId('amount').fill('10');
  await aliceRow.getByTestId('withdraw').click();
  await expect(alice.getByTestId('error')).toContainText('422');
  await expect(alice.getByTestId('error')).toContainText('FROZEN');

  // --- ada unfreezes; alice can withdraw again -------------------------------------------------
  await adaRow.getByTestId('unfreeze').click();
  await expect(adaRow.getByTestId('status')).toHaveText('ACTIVE');

  await alice.reload();
  await alice.locator(`tr[data-account="${accountNumber}"]`).getByTestId('amount').fill('10');
  await alice.locator(`tr[data-account="${accountNumber}"]`).getByTestId('withdraw').click();
  await expect(alice.locator(`tr[data-account="${accountNumber}"] [data-testid="balance"]`)).toContainText('90.00');
});
