// QR-autofill regression test: simulates scanning the TV's QR (URL with PIN)
// while the phone still holds a STALE token from a previous TV session.
// Usage: node qr_pin_sim.js <tv-host> <pin>
const puppeteer = require('puppeteer-core');

const [,, host, pin] = process.argv;
if (!host || !pin) {
  console.error('usage: node qr_pin_sim.js <tv-host> <pin>');
  process.exit(1);
}

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROMIUM || '/usr/bin/chromium',
    headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
  });
  let page = await browser.newPage();
  await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true });
  await page.close(); // placeholder: variants open their own pages

  const events = [];
  page.on('console', (m) => { if (m.type() === 'error') events.push('console: ' + m.text().slice(0, 120)); });
  page.on('response', (r) => { if (r.status() >= 400) events.push('http' + r.status() + ': ' + r.url().slice(-60)); });

  // Seed a stale token (as left over from a previous TV session that restarted),
  // then "scan the QR" — each variant on a FRESH page load (a camera app never
  // does a same-document hash navigation).
  const variants = [
    { label: 'QR ?pin=', url: `http://${host}:8321/?pin=${pin}`, seed: 'stale-dead-token-from-old-session' },
    { label: 'legacy #pin', url: `http://${host}:8321/#${pin}`, seed: 'stale-token-2' },
    { label: '?pin= no token', url: `http://${host}:8321/?pin=${pin}`, seed: null },
    { label: 'wrong pin', url: `http://${host}:8321/?pin=000000`, seed: 'another-stale-token', expectFail: true },
  ];
  for (const v of variants) {
    page = await browser.newPage();
    await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true });
    page.on('console', (m) => { if (m.type() === 'error') events.push(`[${v.label}] console: ` + m.text().slice(0, 120)); });
    page.on('response', (r) => { if (r.status() >= 400 && !r.url().endsWith('/state')) events.push(`[${v.label}] http${r.status()}: ` + r.url().slice(-50)); });
    await page.goto(`http://${host}:8321/`, { waitUntil: 'domcontentloaded' });
    if (v.seed) await page.evaluate((s) => localStorage.setItem('tv_token', s), v.seed);
    else await page.evaluate(() => localStorage.removeItem('tv_token'));
    await page.goto('about:blank');
    await page.goto(v.url, { waitUntil: 'networkidle2', timeout: 30000 });
    await new Promise((r) => setTimeout(r, 5000));
    const st = await page.evaluate(() => ({
      appVisible: !document.getElementById('app').classList.contains('hidden'),
      pairVisible: !document.getElementById('pair').classList.contains('hidden'),
      pairError: (document.getElementById('pairError') || {}).textContent || '',
      token: (localStorage.getItem('tv_token') || '').slice(0, 8),
      cells: document.querySelectorAll('.cell').length,
    }));
    const ok = v.expectFail
      ? st.pairVisible && st.pairError && !st.token
      : st.appVisible && !st.pairVisible && st.token && st.cells > 0;
    console.log(`${v.label}: ${ok ? 'PASS' : 'FAIL'} ${JSON.stringify(st)}`);
    await page.close();
  }

  console.log('EVENTS (' + events.length + '):');
  for (const e of events.slice(0, 20)) console.log(' ', e);
  await browser.close();
})();