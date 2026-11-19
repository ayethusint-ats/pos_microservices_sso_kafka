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

/** Selects the option whose text starts with the given account number. */
async function choose(page: Page, select: 'source' | 'target', accountNumber: string): Promise<void> {
  const value = await page.getByTestId(select).locator('option', { hasText: accountNumber }).getAttribute('value');
  await page.getByTestId(select).selectOption(value ?? '');
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
  await choose(page, 'source', from);
  await choose(page, 'target', to);
  await page.getByTestId('transfer-amount').fill('75');
  await page.getByTestId('send-transfer').click();
  await expect(page.getByTestId('transfer-done')).toBeVisible();
  await expect(page.locator('[data-testid="payments-table"] tbody tr').first().getByTestId('payment-status'))
    .toHaveText('COMPLETED');

  // More than the balance: account-service says 422 INSUFFICIENT_FUNDS, payment-service passes it on.
  await choose(page, 'source', from);
  await choose(page, 'target', to);
  await page.getByTestId('transfer-amount').fill('1000');
  await page.getByTestId('send-transfer').click();
  await expect(page.getByTestId('error-code')).toHaveText('INSUFFICIENT_FUNDS');
  await expect(page.locator('[data-testid="payments-table"] tbody tr').first().getByTestId('payment-status'))
    .toHaveText('REJECTED');

  // Balances: exactly one transfer of 75 happened.
  await page.goto('/accounts');
  await expect(page.locator(`tr[data-account="${from}"] [data-testid="balance"]`)).toContainText('125.00');
  await expect(page.locator(`tr[data-account="${to}"] [data-testid="balance"]`)).toContainText('75.00');
});
