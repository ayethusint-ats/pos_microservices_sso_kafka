import { expect, Page, test } from '@playwright/test';

const PASSWORD = process.env['DEMO_USER_PASSWORD'] ?? '';

async function login(page: Page, username: string, path: string): Promise<void> {
  await page.goto(path);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('current-user')).toHaveText(username);
}

/** Opens a fresh THB account and returns its account number (the test is re-runnable). */
async function openThbAccount(page: Page): Promise<string> {
  const rows = page.locator('[data-testid="accounts-table"] tbody tr');
  await expect(page.locator('[data-testid="accounts-table"], [data-testid="no-accounts"]').first()).toBeVisible();
  const before = (await page.getByTestId('no-accounts').count()) > 0 ? 0 : await rows.count();
  await page.getByTestId('open-account').click();
  await expect(rows).toHaveCount(before + 1);
  return (await rows.last().getAttribute('data-account')) ?? '';
}

/** Picks the source account (own accounts only) by its account number. */
async function chooseSource(page: Page, accountNumber: string): Promise<void> {
  const value = await page.getByTestId('source').locator('option', { hasText: accountNumber }).getAttribute('value');
  await page.getByTestId('source').selectOption(value ?? '');
}

/** Fills in a transfer form and submits it. */
async function send(page: Page, from: string, toNumber: string, amount: string): Promise<void> {
  await chooseSource(page, from);
  await page.getByTestId('target').fill(toNumber);
  await page.getByTestId('transfer-amount').fill(amount);
  await page.getByTestId('send-transfer').click();
}

test('a customer transfers between own accounts; account-service refuses an overdraft', async ({ page }) => {
  test.skip(!PASSWORD, 'Set DEMO_USER_PASSWORD (from ../.env)');

  // Two fresh accounts, the first funded with 200.
  await login(page, 'alice', '/accounts');
  const from = await openThbAccount(page);
  const to = await openThbAccount(page);
  const fromRow = page.locator(`tr[data-account="${from}"]`);
  await fromRow.getByTestId('amount').fill('200');
  await fromRow.getByTestId('deposit').click();
  await expect(fromRow.getByTestId('balance')).toContainText('200.00');

  // Transfer 75 through payment-service (which calls account-service twice to move the money).
  await page.goto('/payments');
  await send(page, from, to, '75');
  await expect(page.getByTestId('transfer-done')).toBeVisible();
  await expect(page.locator('[data-testid="payments-table"] tbody tr').first().getByTestId('payment-status'))
    .toHaveText('COMPLETED');

  // More than the balance: account-service says 422 INSUFFICIENT_FUNDS, payment-service passes it on.
  await send(page, from, to, '1000');
  await expect(page.getByTestId('error-code')).toHaveText('INSUFFICIENT_FUNDS');
  await expect(page.locator('[data-testid="payments-table"] tbody tr').first().getByTestId('payment-status'))
    .toHaveText('REJECTED');

  // Balances: exactly one transfer of 75 happened.
  await page.goto('/accounts');
  await expect(page.locator(`tr[data-account="${from}"] [data-testid="balance"]`)).toContainText('125.00');
  await expect(page.locator(`tr[data-account="${to}"] [data-testid="balance"]`)).toContainText('75.00');
});

test('alice pays bob by account number; bob sees the money arrive', async ({ browser }) => {
  test.skip(!PASSWORD, 'Set DEMO_USER_PASSWORD (from ../.env)');

  // Bob opens an account and tells Alice its number (separate browser session = separate person).
  const bob = await (await browser.newContext()).newPage();
  await login(bob, 'bob', '/accounts');
  const bobsNumber = await openThbAccount(bob);

  // Alice funds a fresh account and sends 40 to Bob's number.
  const alice = await (await browser.newContext()).newPage();
  await login(alice, 'alice', '/accounts');
  const from = await openThbAccount(alice);
  const fromRow = alice.locator(`tr[data-account="${from}"]`);
  await fromRow.getByTestId('amount').fill('100');
  await fromRow.getByTestId('deposit').click();
  await expect(fromRow.getByTestId('balance')).toContainText('100.00');

  await alice.goto('/payments');
  await send(alice, from, bobsNumber, '40');
  await expect(alice.getByTestId('transfer-done')).toBeVisible();
  const latest = alice.locator('[data-testid="payments-table"] tbody tr').first();
  await expect(latest.getByTestId('payment-status')).toHaveText('COMPLETED');
  await expect(latest).toContainText('other customer');

  // Bob's balance went up; Alice's went down.
  await bob.reload();
  await expect(bob.locator(`tr[data-account="${bobsNumber}"] [data-testid="balance"]`)).toContainText('40.00');
  await alice.goto('/accounts');
  await expect(alice.locator(`tr[data-account="${from}"] [data-testid="balance"]`)).toContainText('60.00');

  // An account number that doesn't exist is refused, and nothing moves.
  await alice.goto('/payments');
  await send(alice, from, '0000000000', '1');
  await expect(alice.getByTestId('error-code')).toHaveText('TARGET_ACCOUNT_NOT_FOUND');
});
