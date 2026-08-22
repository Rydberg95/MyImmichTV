(() => {
  const $ = (sel) => document.querySelector(sel);
  const state = {
    token: localStorage.getItem('tv_token') || null,
    tab: 'timeline',
    month: null,
    albumId: null,
    albumName: null,
    months: [],
    assets: [],
    tv: null,
  };

  function buildContext() {
    if (state.tab === 'search') {
      return { source: 'search', assets: state.assets };
    }
    if (state.tab === 'favorites') {
      return { source: 'favorites', bucket: state.month };
    }
    if (state.tab === 'albums' && state.albumId) {
      return { source: 'album', albumId: state.albumId, albumName: state.albumName, bucket: state.month };
    }
    return { source: 'timeline', bucket: state.month };
  }

  function api(path, opts) {
    const base = state.token ? `/r/${state.token}` : '';
    return fetch(base + path, opts).then(async (r) => {
      if (r.status === 403) { logout(); throw new Error('unauthorized'); }
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }
  function command(type, extra) {
    return api('/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.assign({ type: type }, extra || {})),
    });
  }
  function logout() {
    localStorage.removeItem('tv_token');
    state.token = null;
    location.hash = '';
    showPair();
  }

  function showPair() {
    $('#pair').classList.remove('hidden');
    $('#app').classList.add('hidden');
    $('#pinInput').focus();
  }
  function showApp() {
    $('#pair').classList.add('hidden');
    $('#app').classList.remove('hidden');
    loadMonths();
    pollState();
  }

  async function pair(pin) {
    const r = await fetch('/api/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin: pin }),
    });
    if (!r.ok) throw new Error('bad pin');
    const data = await r.json();
    state.token = data.token;
    localStorage.setItem('tv_token', data.token);
    location.hash = '';
    showApp();
  }

  $('#pairForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const pin = $('#pinInput').value.trim();
    if (pin.length !== 6) return;
    $('#pairError').textContent = '';
    try { await pair(pin); }
    catch (err) { $('#pairError').textContent = 'Wrong PIN'; }
  });

  function renderMonths() {
    const el = $('#months');
    el.innerHTML = '';
    const list = state.tab === 'albums' ? [] : state.months;
    el.style.display = state.tab === 'albums' || state.tab === 'search' ? 'none' : 'flex';
    list.forEach((m) => {
      const b = document.createElement('button');
      b.textContent = m.timeBucket;
      if (m.timeBucket === state.month) b.classList.add('active');
      b.addEventListener('click', () => {
        state.month = m.timeBucket;
        renderMonths();
        loadAssets();
      });
      el.appendChild(b);
    });
  }

  async function loadMonths() {
    try {
      let q = '';
      if (state.tab === 'favorites') q = '?favorite=true';
      else if (state.tab === 'albums' && state.albumId) q = '?album=' + state.albumId;
      state.months = await api('/buckets' + q);
      if (!state.months.length) { state.month = null; renderMonths(); renderAssets([]); return; }
      if (!state.month || !state.months.some(m => m.timeBucket === state.month)) {
        state.month = state.months[0].timeBucket;
      }
      renderMonths();
      loadAssets();
    } catch (e) { console.error(e); }
  }

  async function loadAssets() {
    if (!state.month) return;
    let q = '?bucket=' + encodeURIComponent(state.month);
    if (state.tab === 'favorites') q += '&favorite=true';
    if (state.tab === 'albums' && state.albumId) q += '&album=' + state.albumId;
    try {
      state.assets = await api('/assets' + q);
      renderAssets(state.assets);
    } catch (e) { console.error(e); }
  }

  function imgSrc(id, size) {
    return `/r/${state.token}/thumb/${id}?size=${size || 'thumbnail'}`;
  }

  function renderAssets(assets) {
    const main = $('#content');
    main.innerHTML = '';
    if (state.tab === 'search') { renderSearch(); return; }
    if (state.tab === 'albums' && !state.albumId) { renderAlbums(); return; }
    fillGrid(assets);
  }

  function fillGrid(assets) {
    const main = $('#content');
    if (!assets.length) {
      if (!document.querySelector('.grid')) main.innerHTML = '<div class="empty">No photos here</div>';
      return;
    }
    const old = document.querySelector('.grid');
    if (old) old.remove();
    const grid = document.createElement('div');
    grid.className = 'grid';
    assets.forEach((a) => {
      const cell = document.createElement('div');
      cell.className = 'cell' + (state.tv && state.tv.assetId === a.id ? ' current' : '');
      const img = document.createElement('img');
      img.loading = 'lazy';
      img.src = imgSrc(a.id);
      img.alt = '';
      cell.appendChild(img);
      if (a.type === 'VIDEO') {
        const v = document.createElement('span');
        v.className = 'vid';
        v.textContent = '▶';
        cell.appendChild(v);
      }
      cell.addEventListener('click', () => {
        command('show', { assetId: a.id, assetType: a.type, context: buildContext() });
      });
      grid.appendChild(cell);
    });
    main.appendChild(grid);
  }

  async function renderAlbums() {
    const main = $('#content');
    try {
      const albums = await api('/albums');
      main.innerHTML = '';
      if (!albums.length) {
        main.innerHTML = '<div class="empty">No albums</div>';
        return;
      }
      const wrap = document.createElement('div');
      wrap.className = 'albums';
      albums.forEach((al) => {
        const d = document.createElement('div');
        d.className = 'album';
        const h = document.createElement('h3');
        h.textContent = al.name;
        const p = document.createElement('p');
        p.textContent = al.count + ' items';
        d.appendChild(h);
        d.appendChild(p);
        d.addEventListener('click', () => {
          state.albumId = al.id;
          state.albumName = al.name;
          state.month = null;
          $('#months').style.display = 'flex';
          loadMonths();
        });
        wrap.appendChild(d);
      });
      main.appendChild(wrap);
    } catch (e) { console.error(e); }
  }

  function renderSearch() {
    const main = $('#content');
    main.innerHTML = '';
    const bar = document.createElement('div');
    bar.className = 'searchbar';
    const input = document.createElement('input');
    input.type = 'search';
    input.placeholder = 'Search photos… (e.g. "beach sunset")';
    let timer = null;
    input.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const q = input.value.trim();
        if (!q) { fillGrid([]); return; }
        try {
          const assets = await api('/search?q=' + encodeURIComponent(q));
          state.assets = assets;
          fillGrid(assets);
        } catch (e) { console.error(e); }
      }, 350);
    });
    bar.appendChild(input);
    main.appendChild(bar);
    const grid = document.createElement('div');
    grid.className = 'grid';
    main.appendChild(grid);
  }

  document.querySelectorAll('nav button').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.tab = btn.dataset.tab;
      state.albumId = null;
      state.albumName = null;
      state.month = null;
      document.querySelectorAll('nav button').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      if (state.tab === 'search') {
        $('#months').style.display = 'none';
        renderAssets([]);
      } else if (state.tab === 'albums') {
        renderAssets([]);
      } else {
        loadMonths();
      }
    });
  });

  $('#btnPrev').addEventListener('click', () => command('prev'));
  $('#btnNext').addEventListener('click', () => command('next'));
  $('#btnPlay').addEventListener('click', () => {
    const on = !(state.tv && state.tv.slideshow);
    command('slideshow', { value: on ? 1 : 0 });
    if (state.tv) state.tv.slideshow = on;
    syncControls();
  });
  $('#btnShuffle').addEventListener('click', () => {
    const on = !(state.tv && state.tv.shuffle);
    command('shuffle', { value: on ? 1 : 0 });
    if (state.tv) state.tv.shuffle = on;
    syncControls();
  });
  $('#btnInfo').addEventListener('click', () => command('info'));

  function syncControls() {
    $('#btnPlay').classList.toggle('on', !!(state.tv && state.tv.slideshow));
    $('#btnShuffle').classList.toggle('on', !!(state.tv && state.tv.shuffle));
    const cells = document.querySelectorAll('.cell');
    cells.forEach((c) => c.classList.remove('current'));
    if (state.tv && state.tv.assetId) {
      const idx = state.assets.findIndex(a => a.id === state.tv.assetId);
      if (idx >= 0 && cells[idx]) cells[idx].classList.add('current');
    }
    const now = $('#nowPlaying');
    if (state.tv && state.tv.assetId) {
      const src = state.tv.source === 'ALBUM' ? state.tv.label
        : state.tv.source.charAt(0) + state.tv.source.slice(1).toLowerCase();
      now.textContent = 'Showing ' + (state.tv.index + 1) + ' / ' + state.tv.total + ' · ' + src;
    } else {
      now.textContent = '';
    }
  }

  let pollBusy = false;
  async function pollState() {
    if (pollBusy) return;
    pollBusy = true;
    try {
      state.tv = await api('/state');
      $('#connState').classList.remove('off');
      syncControls();
    } catch (e) {
      $('#connState').classList.add('off');
    } finally {
      pollBusy = false;
    }
  }
  setInterval(pollState, 2000);

  const setup = {
    pin: null,
    polling: null,
    url: null,
    key: null,
  };

  function showSetup(hashPin) {
    $('#pair').classList.add('hidden');
    $('#app').classList.add('hidden');
    $('#setup').classList.remove('hidden');
    if (hashPin) $('#setupPin').value = hashPin;
    $('#setupPin').focus();
  }

  function hideSetup() {
    $('#setup').classList.add('hidden');
    if (setup.polling) { clearInterval(setup.polling); setup.polling = null; }
  }

  function setSetupStatus(text, isError) {
    $('#setupStatus').textContent = text || '';
    $('#setupError').textContent = isError ? text : '';
    if (!isError) $('#setupStatus').textContent = text || '';
  }

  async function submitSetup(acceptCert) {
    const pin = $('#setupPin').value.trim();
    const url = $('#setupUrl').value.trim();
    const key = $('#setupKey').value.trim();
    if (pin.length !== 6 || !url || !key) {
      setSetupStatus('Fill in PIN, server URL and API key', true);
      return;
    }
    setup.pin = pin; setup.url = url; setup.key = key;
    $('#setupCert').classList.add('hidden');
    setSetupStatus('Contacting server…');
    try {
      await fetch('/setup/submit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pin: pin, url: url, apiKey: key }),
      });
      if (!setup.polling) {
        setup.polling = setInterval(pollSetup, 1200);
        pollSetup();
      }
    } catch (e) {
      setSetupStatus('Cannot reach the TV', true);
    }
  }

  async function pollSetup() {
    try {
      const s = await fetch('/setup/status').then((r) => r.json());
      if (s.phase === 'AWAITING_CONFIRM') {
        $('#setupFp').textContent = s.fingerprint;
        $('#setupCert').classList.remove('hidden');
        setSetupStatus('Confirm the certificate fingerprint');
      } else if (s.phase === 'PROBING') {
        setSetupStatus('Contacting ' + (s.url || 'server') + '…');
      } else if (s.phase === 'CONNECTING') {
        setSetupStatus('Validating API key…');
      } else if (s.phase === 'DONE' || s.configured) {
        hideSetup();
        await pair(setup.pin || $('#setupPin').value.trim());
      } else if (s.phase === 'ERROR') {
        setSetupStatus('Error: ' + (s.error || 'unknown'), true);
        if (setup.polling) { clearInterval(setup.polling); setup.polling = null; }
      }
    } catch (e) { /* transient */ }
  }

  $('#setupForm').addEventListener('submit', (e) => {
    e.preventDefault();
    submitSetup(false);
  });
  $('#setupTrust').addEventListener('click', async () => {
    try {
      await fetch('/setup/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pin: setup.pin }),
      });
      setSetupStatus('Saving…');
    } catch (e) { setSetupStatus('Cannot reach the TV', true); }
  });
  $('#setupCancel').addEventListener('click', async () => {
    $('#setupCert').classList.add('hidden');
    try {
      await fetch('/setup/cancel', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pin: setup.pin }),
      });
    } catch (e) {}
    setSetupStatus('');
  });

  window.addEventListener('error', (e) => {
    const el = document.createElement('pre');
    el.style.cssText = 'position:fixed;bottom:0;left:0;right:0;background:#5c1a1a;color:#ef9a9a;font-size:11px;padding:6px;z-index:99;white-space:pre-wrap;';
    el.textContent = 'JS error: ' + e.message;
    document.body.appendChild(el);
  });

  const hashPin = (location.hash || '').replace(/^#/, '').trim();

  async function boot() {
    let status = null;
    try {
      status = await fetch('/api/status').then((r) => r.json());
    } catch (e) {}
    if (status && !status.configured) {
      showSetup(hashPin);
      return;
    }
    if (hashPin && !state.token) {
      $('#pinInput').value = hashPin;
      pair(hashPin).catch(() => { $('#pairError').textContent = 'Wrong PIN'; });
    } else if (state.token) {
      fetch(`/r/${state.token}/state`).then((r) => {
        if (r.ok) showApp(); else logout();
      }).catch(() => showPair());
    } else {
      showPair();
    }
  }
  boot();
})();
