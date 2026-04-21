import { chromium } from 'playwright';
import { mkdirSync } from 'fs';
import { resolve } from 'path';

const URL = process.env.URL || 'http://127.0.0.1:5174';
const OUT = resolve('docs/screenshots');
mkdirSync(OUT, { recursive: true });

async function capture(viewport, filename, { openSession = false } = {}) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport, deviceScaleFactor: 2 });
  const page = await context.newPage();

  await page.goto(URL, { waitUntil: 'networkidle' });

  // Wait for the host list to render (`.host` appears once /api/hosts
  // resolves through the dev proxy).
  await page.waitForSelector('.host', { timeout: 8000 });

  if (openSession) {
    await page.click('.host');
    await page.click('button:has-text("Open session")');
    // Wait for the session meta to show we're attached.
    await page.waitForSelector('.meta >> text=session sess_', { timeout: 5000 });
    // Send a prompt so the mock adapter replays its scripted stream.
    await page.fill('.composer input', 'extract the jwt verify path');
    await page.click('.composer button:has-text("Send")');
    // Let the full mocked turn play out.
    await page.waitForTimeout(3000);
  }

  const out = resolve(OUT, filename);
  await page.screenshot({ path: out, fullPage: false });
  console.log('Wrote', out);

  await browser.close();
}

await capture({ width: 1440, height: 900 }, 'client-desktop.png');
await capture({ width: 390, height: 844 }, 'client-mobile.png');
await capture({ width: 1440, height: 900 }, 'client-session.png', { openSession: true });
