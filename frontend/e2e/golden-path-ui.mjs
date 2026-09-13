import { chromium } from 'playwright';

const baseUrl = process.env.TJ_FRONTEND_URL || 'http://127.0.0.1:5173';
const bidderId = process.env.TJ_E2E_BIDDER_ID;
const hostName = process.env.TJ_E2E_HOST_NAME || 'Host Frontend E2E';

if (!bidderId) throw new Error('TJ_E2E_BIDDER_ID is required');

const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext();
  await context.addInitScript((actorId) => {
    localStorage.setItem('tj.devActorId', actorId);
  }, bidderId);
  const page = await context.newPage();
  await page.goto(`${baseUrl}/#/flow`, { waitUntil: 'networkidle' });

  await page.getByRole('heading', { name: 'De identidad a Wallet, sin IDs manuales' }).waitFor();
  await page.getByText('BIDDER', { exact: true }).waitFor();
  await page.getByText('VERIFIED 18+', { exact: true }).waitFor();

  await page.getByRole('button', { name: 'Buscar HOSTS' }).click();
  const hostCard = page.getByRole('button', { name: new RegExp(hostName) });
  await hostCard.waitFor();
  await hostCard.click();

  await page.getByRole('button', { name: 'Crear Proposal' }).click();
  await page.getByText(/Proposal ACTIVE/).waitFor();

  await page.getByRole('button', { name: 'Cargar Auctions ONLINE abiertas' }).click();
  const auctionCard = page.locator('.list-card').first();
  await auctionCard.waitFor();
  await auctionCard.click();
  await page.getByRole('button', { name: /Ganar Ahora/ }).click();

  const confirm = page.getByRole('button', { name: 'Confirmar Winner' });
  await confirm.waitFor();
  await confirm.click();
  await page.locator('[data-testid="webrtc-panel"]').waitFor();

  await page.getByRole('button', { name: 'Actualizar Wallet' }).click();
  await page.getByText('Pending', { exact: true }).waitFor();
  await page.getByText('Available', { exact: true }).waitFor();

  await page.getByPlaceholder('Describe el incidente').fill('Reporte E2E de interfaz para validar el flujo separado de Safety.');
  await page.getByRole('button', { name: 'Reportar' }).click();
  await page.getByText(/Reporte creado/).waitFor();

  await page.getByRole('button', { name: 'Bloquear' }).click();
  await page.getByRole('button', { name: 'Desbloquear' }).waitFor();

  console.log('PASS: browser Golden Path reached Session, Wallet, report and block without manual UUID entry');
} finally {
  await browser.close();
}
