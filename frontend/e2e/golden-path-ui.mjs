import { chromium } from 'playwright';

const baseUrl = process.env.TJ_FRONTEND_URL || 'http://127.0.0.1:5173';
const bidderId = process.env.TJ_E2E_BIDDER_ID;
const hostName = process.env.TJ_E2E_HOST_NAME || 'Host Frontend E2E';

if (!bidderId) throw new Error('TJ_E2E_BIDDER_ID is required');

async function waitForSuccessOrError(page, successLocator, label, timeout = 10000) {
  const success = successLocator.waitFor({ state: 'visible', timeout }).then(() => ({ ok: true }));
  const failure = page.locator('.error-banner').waitFor({ state: 'visible', timeout })
    .then(async () => ({ ok: false, message: await page.locator('.error-banner').innerText() }));
  const result = await Promise.race([success, failure]);
  if (!result.ok) throw new Error(`${label}: ${result.message}`);
}

const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext();
  await context.addInitScript((actorId) => {
    localStorage.setItem('tj.devActorId', actorId);
  }, bidderId);
  const page = await context.newPage();
  page.on('response', async (response) => {
    if (response.url().includes('/api/v1/') && response.status() >= 400) {
      let body = '';
      try { body = await response.text(); } catch { body = '<unreadable>'; }
      console.error(`API ${response.status()} ${response.request().method()} ${response.url()} ${body}`);
    }
  });
  await page.goto(`${baseUrl}/#/flow`, { waitUntil: 'networkidle' });

  await page.getByRole('heading', { name: 'De identidad a Wallet, sin IDs manuales' }).waitFor();
  await page.getByText('BIDDER', { exact: true }).waitFor();
  await page.getByText('VERIFIED 18+', { exact: true }).waitFor();

  await page.getByRole('button', { name: 'Buscar HOSTS' }).click();
  const hostCard = page.getByRole('button', { name: new RegExp(hostName) });
  await hostCard.waitFor();
  await hostCard.click();

  await page.getByRole('button', { name: 'Crear Proposal' }).click();
  await waitForSuccessOrError(page, page.getByText(/Proposal ACTIVE/), 'Proposal creation');

  await page.getByRole('button', { name: 'Cargar Auctions ONLINE abiertas' }).click();
  const auctionCard = page.locator('.list-card').first();
  await waitForSuccessOrError(page, auctionCard, 'Auction discovery');
  await auctionCard.click();
  await page.getByRole('button', { name: /Ganar Ahora/ }).click();

  const confirm = page.getByRole('button', { name: 'Confirmar Winner' });
  await confirm.waitFor();
  await confirm.click();
  await waitForSuccessOrError(page, page.locator('[data-testid="webrtc-panel"]'), 'Winner confirmation / Session');

  await page.getByRole('button', { name: 'Actualizar Wallet' }).click();
  await waitForSuccessOrError(page, page.getByText('Pending', { exact: true }), 'Wallet');
  await page.getByText('Available', { exact: true }).waitFor();

  await page.getByPlaceholder('Describe el incidente').fill('Reporte E2E de interfaz para validar el flujo separado de Safety.');
  await page.getByRole('button', { name: 'Reportar' }).click();
  await waitForSuccessOrError(page, page.getByText(/Reporte creado/), 'Safety report');

  await page.getByRole('button', { name: 'Bloquear' }).click();
  await waitForSuccessOrError(page, page.getByRole('button', { name: 'Desbloquear' }), 'User block');

  console.log('PASS: browser Golden Path reached Session, Wallet, report and block without manual UUID entry');
} finally {
  await browser.close();
}
