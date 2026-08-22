const puppeteer = require('puppeteer-core');

const [,, host, pin, ...rest] = process.argv;
if (!host || !pin) {
  console.error('usage: node phone_sim.js <tv-host> <pin>');
  console.error('   ex: node phone_sim.js 192.168.50.122 123456');
  process.exit(1);
}

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROMIUM || '/usr/bin/chromium',
    headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true });

  const events = [];
  page.on('console', (m) => {
    if (m.type() === 'error') events.push('console: ' + m.text().slice(0, 150));
  });
  page.on('requestfailed', (r) => {
    events.push('reqfail: ' + r.url().slice(-70) + ' ' + (r.failure() ? r.failure().errorText : ''));
  });
  page.on('response', (r) => {
    if (r.status() >= 400) events.push('http' + r.status() + ': ' + r.url().slice(-70));
  });

  await page.goto(`http://${host}:8321/#${pin}`, { waitUntil: 'networkidle2', timeout: 30000 });
  await new Promise((r) => setTimeout(r, 6000));
  await page.screenshot({ path: 'phone_sim.png' });

  const st = await page.evaluate(() => {
    const imgs = [...document.querySelectorAll('.cell img')];
    return {
      appVisible: !document.getElementById('app').classList.contains('hidden'),
      setupVisible: !document.getElementById('setup').classList.contains('hidden'),
      pairVisible: !document.getElementById('pair').classList.contains('hidden'),
      months: document.querySelectorAll('#months button').length,
      cells: document.querySelectorAll('.cell').length,
      brokenInView: imgs.filter((i) => i.complete && i.naturalWidth === 0).length,
      token: (localStorage.getItem('tv_token') || '').slice(0, 8),
      contentText: document.getElementById('content').textContent.trim().slice(0, 80),
    };
  });

  console.log('PAGE STATE:', JSON.stringify(st, null, 1));
  console.log('EVENTS (' + events.length + '):');
  for (const e of events.slice(0, 30)) console.log(' ', e);
  await browser.close();
})();
