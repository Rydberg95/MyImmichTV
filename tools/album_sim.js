const puppeteer = require('puppeteer-core');

const [,, host, pin] = process.argv;
if (!host || !pin) {
  console.error('usage: node album_sim.js <tv-host> <pin>');
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
  await new Promise((r) => setTimeout(r, 4000));

  // click the Albums tab
  await page.evaluate(() => document.querySelector('nav button[data-tab="albums"]').click());
  await new Promise((r) => setTimeout(r, 4000));
  await page.screenshot({ path: 'album_sim.png' });

  const st = await page.evaluate(() => {
    const albums = [...document.querySelectorAll('.album')];
    const covers = [...document.querySelectorAll('.album .album-cover')];
    const footerSvgs = [...document.querySelectorAll('footer button svg')];
    return {
      albumCards: albums.length,
      cardsWithCover: covers.filter((i) => i.complete && i.naturalWidth > 0).length,
      monogramCards: document.querySelectorAll('.album.noimg').length,
      firstCardText: (albums[0] || {}).textContent,
      footerButtons: document.querySelectorAll('footer button').length,
      footerSvgs: footerSvgs.length,
      playPausePair: {
        playHidden: document.querySelector('#btnPlay .ic-play').classList.contains('hidden'),
        pauseHidden: document.querySelector('#btnPlay .ic-pause').classList.contains('hidden'),
      },
      activeTab: (document.querySelector('nav button.active') || {}).dataset,
      bodyBg: getComputedStyle(document.body).backgroundColor,
      accentVar: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(),
    };
  });

  // tap the first album card → should load its stream
  await page.evaluate(() => document.querySelector('.album').click());
  await new Promise((r) => setTimeout(r, 4000));
  const st2 = await page.evaluate(() => ({
    cells: document.querySelectorAll('.cell').length,
    broken: [...document.querySelectorAll('.cell img')].filter((i) => i.complete && i.naturalWidth === 0).length,
  }));
  await page.screenshot({ path: 'album_stream.png' });

  console.log('ALBUMS TAB:', JSON.stringify(st, null, 1));
  console.log('AFTER TAP:', JSON.stringify(st2));
  console.log('EVENTS (' + events.length + '):');
  for (const e of events.slice(0, 20)) console.log(' ', e);
  await browser.close();
})();
