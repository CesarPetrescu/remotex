import { chromium } from 'playwright';
import { mkdirSync } from 'fs';
import { resolve } from 'path';

const URL = process.env.URL || 'http://localhost:5173';
const OUT = resolve('docs/screenshots');
mkdirSync(OUT, { recursive: true });

async function capture(tabKey, viewport, filename, deviceScaleFactor = 2) {
  const launchOpts = {};
  if (process.env.CHROMIUM_PATH) launchOpts.executablePath = process.env.CHROMIUM_PATH;
  const browser = await chromium.launch(launchOpts);
  const context = await browser.newContext({ viewport, deviceScaleFactor });
  const page = await context.newPage();

  await page.addInitScript((key) => {
    try { localStorage.setItem('remotex.tab', key); } catch {}
  }, tabKey);

  await page.goto(URL, { waitUntil: 'networkidle' });
  await page.waitForSelector('.panel[data-active]');
  await page.waitForTimeout(400);

  const out = resolve(OUT, filename);
  await page.screenshot({ path: out, fullPage: true });
  console.log('Wrote', out);

  await browser.close();
}

await capture('desk', { width: 1440, height: 900 }, 'webapp-desktop.png');
await capture('mob', { width: 390, height: 844 }, 'webapp-mobile.png');
