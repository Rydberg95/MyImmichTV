(() => {
  const $ = (sel) => document.querySelector(sel);
  const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN',
    'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

  const state = {
    token: localStorage.getItem('tv_token') || null,
    tab: 'timeline',
    albumId: null,
    albumName: null,
    assets: [],
    tv: null,
  };

  const mainEl = $('#content');
  const railEl = $('#rail');
  const thumbEl = railEl.querySelector('.rail-thumb');
  const bubbleEl = railEl.querySelector('.rail-bubble');
  const ticksEl = railEl.querySelector('.rail-ticks');

  // ---- endless-scroll stream state ----
  // months: newest-first [{timeBucket, count}]; cum[i] = photos in months[0..i-1]
  // win: contiguous window of loaded months [start, end); segs mirror the DOM order
  const stream = {
    months: [],
    cum: [0],
    total: 0,
    segs: [],
    win: { start: 0, end: 0 },
    seq: 0,
    appendBusy: false,
    prependBusy: false,
    moreEl: null,
  };

  function buildContext(bucket) {
    if (state.tab === 'favorites') {
      return { source: 'favorites', bucket: bucket };
    }
    if (state.tab === 'albums' && state.albumId) {
      return { source: 'album', albumId: state.albumId, albumName: state.albumName, bucket: bucket };
    }
    return { source: 'timeline', bucket: bucket };
  }

  function api(path, opts) {
    const base = state.token ? `/r/${state.token}` : '';
    return fetch(base + path, opts).then(async (r) => {
      if (r.status === 403) { logout(); throw new Error('unauthorized'); }
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const text = await r.text();
      return text ? JSON.parse(text) : null;
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
    loadStream();
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

  // ---- stream: loading ----

  function monthLabel(bucket) {
    const m = +bucket.slice(5, 7);
    return (MONTHS[m - 1] || '?') + ' ' + bucket.slice(0, 4);
  }
  function sourceParams() {
    if (state.tab === 'favorites') return '&favorite=true';
    if (state.tab === 'albums' && state.albumId) return '&album=' + encodeURIComponent(state.albumId);
    return '';
  }

  function clearStream() {
    stream.seq++;
    stream.months = [];
    stream.cum = [0];
    stream.total = 0;
    stream.segs = [];
    stream.win = { start: 0, end: 0 };
    stream.appendBusy = false;
    stream.prependBusy = false;
    stream.moreEl = null;
    mainEl.innerHTML = '';
    railEl.classList.add('hidden');
    ticksEl.innerHTML = '';
    bubbleEl.textContent = '';
  }

  async function loadStream() {
    clearStream();
    const seq = stream.seq;
    let q = '';
    if (state.tab === 'favorites') q = '?favorite=true';
    else if (state.tab === 'albums' && state.albumId) q = '?album=' + encodeURIComponent(state.albumId);
    try {
      const buckets = (await api('/buckets' + q))
        .filter((m) => m.count > 0)
        .sort((a, b) => b.timeBucket.localeCompare(a.timeBucket));
      if (seq !== stream.seq) return;
      stream.months = buckets;
      stream.cum = [0];
      buckets.forEach((m) => stream.cum.push(stream.cum[stream.cum.length - 1] + m.count));
      stream.total = stream.cum[stream.cum.length - 1];
      if (!buckets.length) {
        mainEl.innerHTML = '<div class="empty">No photos here</div>';
        return;
      }
      stream.moreEl = document.createElement('div');
      stream.moreEl.className = 'stream-more';
      stream.moreEl.textContent = 'Loading more…';
      mainEl.appendChild(stream.moreEl);
      buildRailTicks();
      railEl.classList.remove('hidden');
      updateRail();
      await appendMonth();
    } catch (e) { console.error(e); }
  }

  function fetchMonth(i) {
    return api('/assets?bucket=' + encodeURIComponent(stream.months[i].timeBucket) + sourceParams());
  }

  async function appendMonth() {
    if (stream.appendBusy || !stream.moreEl) return;
    if (stream.win.end >= stream.months.length) { stream.moreEl.classList.remove('show'); return; }
    stream.appendBusy = true;
    const seq = stream.seq;
    const i = stream.win.end;
    let chained = false;
    stream.moreEl.classList.add('show');
    try {
      const assets = await fetchMonth(i);
      if (seq !== stream.seq || stream.win.end !== i) return;
      const el = buildSegEl(i, assets);
      mainEl.insertBefore(el, stream.moreEl);
      stream.win.end = i + 1;
      stream.segs.push({ i: i, el: el });
      if (stream.win.end >= stream.months.length) stream.moreEl.classList.remove('show');
      updateRail();
      chained = true;
    } catch (e) {
      console.error(e);
    } finally {
      stream.appendBusy = false;
    }
    if (chained) maybeLoadMore();
  }

  async function prependMonth() {
    if (stream.prependBusy || stream.win.start <= 0) return;
    stream.prependBusy = true;
    const seq = stream.seq;
    const i = stream.win.start - 1;
    let chained = false;
    try {
      const assets = await fetchMonth(i);
      if (seq !== stream.seq || stream.win.start !== i + 1) return;
      const el = buildSegEl(i, assets);
      const before = mainEl.scrollHeight;
      mainEl.insertBefore(el, mainEl.firstChild);
      mainEl.scrollTop += mainEl.scrollHeight - before;
      stream.win.start = i;
      stream.segs.unshift({ i: i, el: el });
      updateRail();
      chained = mainEl.scrollTop < 300 && stream.win.start > 0;
    } catch (e) {
      console.error(e);
    } finally {
      stream.prependBusy = false;
    }
    if (chained) prependMonth();
  }

  function maybeLoadMore() {
    if (!stream.months.length || railEl.classList.contains('hidden')) return;
    if (mainEl.scrollHeight - mainEl.scrollTop - mainEl.clientHeight < 600) appendMonth();
    if (mainEl.scrollTop < 300 && stream.win.start > 0) prependMonth();
  }

  async function jumpTo(i) {
    if (i < 0 || i >= stream.months.length) return;
    const seg = stream.segs.find((s) => s.i === i);
    if (seg) {
      mainEl.scrollTop =
        seg.el.getBoundingClientRect().top - mainEl.getBoundingClientRect().top + mainEl.scrollTop;
      return;
    }
    // target month not loaded: reset the window to start there
    stream.seq++;
    const seq = stream.seq;
    Array.prototype.forEach.call(mainEl.querySelectorAll('.grid'), (g) => g.remove());
    stream.segs = [];
    stream.win = { start: i, end: i };
    mainEl.scrollTop = 0;
    await appendMonth();
    if (seq !== stream.seq) return;
    maybeLoadMore();
  }

  // ---- stream: rendering ----

  function imgSrc(id, size) {
    return `/r/${state.token}/thumb/${id}?size=${size || 'thumbnail'}`;
  }

  function makeCell(a, ctx) {
    const cell = document.createElement('div');
    cell.className = 'cell';
    cell.dataset.id = a.id;
    const img = document.createElement('img');
    img.loading = 'lazy';
    img.src = imgSrc(a.id);
    img.alt = '';
    cell.appendChild(img);
    if (a.type === 'VIDEO') {
      const v = document.createElement('span');
      v.className = 'vid';
      v.innerHTML = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
      cell.appendChild(v);
    }
    cell.addEventListener('click', () => {
      command('show', { assetId: a.id, assetType: a.type, context: ctx });
    });
    return cell;
  }

  function buildSegEl(i, assets) {
    const ctx = buildContext(stream.months[i].timeBucket);
    const grid = document.createElement('div');
    grid.className = 'grid';
    grid.dataset.bucket = stream.months[i].timeBucket;
    assets.forEach((a) => grid.appendChild(makeCell(a, ctx)));
    return grid;
  }

  function fillGrid(assets) {
    if (!assets.length) {
      if (!document.querySelector('.grid')) mainEl.innerHTML = '<div class="empty">No photos here</div>';
      return;
    }
    const old = document.querySelector('.grid');
    if (old) old.remove();
    const ctx = { source: 'search', assets: assets };
    const grid = document.createElement('div');
    grid.className = 'grid';
    assets.forEach((a) => grid.appendChild(makeCell(a, ctx)));
    mainEl.appendChild(grid);
  }

  async function renderAlbums() {
    clearStream();
    try {
      const albums = await api('/albums');
      mainEl.innerHTML = '';
      if (!albums.length) {
        mainEl.innerHTML = '<div class="empty">No albums</div>';
        return;
      }
      const wrap = document.createElement('div');
      wrap.className = 'albums';
      albums.forEach((al) => {
        const d = document.createElement('div');
        d.className = 'album';
        d.dataset.initial = (al.name || '?').trim().charAt(0).toUpperCase() || '?';
        if (al.thumbId) {
          const img = document.createElement('img');
          img.className = 'album-cover';
          img.loading = 'lazy';
          img.alt = '';
          img.src = imgSrc(al.thumbId, 'thumbnail');
          img.addEventListener('error', () => {
            img.remove();
            d.classList.add('noimg');
          });
          d.appendChild(img);
        } else {
          d.classList.add('noimg');
        }
        const meta = document.createElement('div');
        meta.className = 'album-meta';
        const h = document.createElement('h3');
        h.textContent = al.name;
        const p = document.createElement('p');
        p.textContent = al.count + (al.count === 1 ? ' item' : ' items');
        meta.appendChild(h);
        meta.appendChild(p);
        d.appendChild(meta);
        d.addEventListener('click', () => {
          state.albumId = al.id;
          state.albumName = al.name;
          loadStream();
        });
        wrap.appendChild(d);
      });
      mainEl.appendChild(wrap);
    } catch (e) { console.error(e); }
  }

  function renderSearch() {
    clearStream();
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
    mainEl.appendChild(bar);
    const grid = document.createElement('div');
    grid.className = 'grid';
    mainEl.appendChild(grid);
  }

  // ---- rail (year/month scrubber) ----

  function topSegIndex() {
    const probe = mainEl.getBoundingClientRect().top + 40;
    for (let k = 0; k < stream.segs.length; k++) {
      if (stream.segs[k].el.getBoundingClientRect().bottom > probe) return stream.segs[k].i;
    }
    return stream.segs.length ? stream.segs[stream.segs.length - 1].i : stream.win.start;
  }

  function updateRail() {
    if (railEl.classList.contains('hidden') || !stream.months.length) return;
    const railH = railEl.clientHeight;
    if (!railH) return;
    const inset = 6;
    const usable = Math.max(1, railH - inset * 2);
    const loaded = stream.cum[stream.win.end] - stream.cum[stream.win.start];
    const thumbH = Math.max(28, Math.min(usable, Math.round(usable * loaded / stream.total)));
    const cur = topSegIndex();
    const pos = stream.cum[cur] / stream.total;
    thumbEl.style.height = thumbH + 'px';
    thumbEl.style.top = (inset + Math.min(Math.round(pos * usable), usable - thumbH)) + 'px';
    if (!drag.active) bubbleEl.textContent = monthLabel(stream.months[cur].timeBucket);
  }

  function buildRailTicks() {
    ticksEl.innerHTML = '';
    for (let i = 1; i < stream.months.length; i++) {
      const y = stream.months[i].timeBucket.slice(0, 4);
      if (y !== stream.months[i - 1].timeBucket.slice(0, 4)) {
        const t = document.createElement('span');
        t.className = 'rail-tick';
        t.textContent = y;
        t.style.top = (stream.cum[i] / stream.total * 100) + '%';
        ticksEl.appendChild(t);
      }
    }
  }

  function railIndexAt(clientY) {
    const r = railEl.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (clientY - r.top) / r.height));
    const target = ratio * stream.total;
    let i = 0;
    while (i + 1 < stream.cum.length && stream.cum[i + 1] <= target) i++;
    return i;
  }

  const drag = { active: false, target: null, timer: null };

  function onRailPoint(clientY, final) {
    if (!stream.months.length) return;
    const i = railIndexAt(clientY);
    drag.target = i;
    bubbleEl.textContent = monthLabel(stream.months[i].timeBucket);
    const seg = stream.segs.find((s) => s.i === i);
    if (seg) {
      clearTimeout(drag.timer); drag.timer = null;
      mainEl.scrollTop =
        seg.el.getBoundingClientRect().top - mainEl.getBoundingClientRect().top + mainEl.scrollTop;
    } else if (final) {
      clearTimeout(drag.timer); drag.timer = null;
      jumpTo(i);
    } else {
      clearTimeout(drag.timer);
      drag.timer = setTimeout(() => {
        if (drag.active && drag.target === i) jumpTo(i);
      }, 250);
    }
  }

  railEl.addEventListener('pointerdown', (e) => {
    if (railEl.classList.contains('hidden')) return;
    e.preventDefault();
    drag.active = true;
    railEl.classList.add('drag');
    railEl.setPointerCapture(e.pointerId);
    onRailPoint(e.clientY, false);
  });
  railEl.addEventListener('pointermove', (e) => {
    if (drag.active) onRailPoint(e.clientY, false);
  });
  function endDrag() {
    if (!drag.active) return;
    drag.active = false;
    railEl.classList.remove('drag');
    const t = drag.target;
    drag.target = null;
    clearTimeout(drag.timer);
    drag.timer = null;
    if (t !== null && t !== topSegIndex()) jumpTo(t);
  }
  railEl.addEventListener('pointerup', endDrag);
  railEl.addEventListener('pointercancel', endDrag);

  let railRaf = 0;
  mainEl.addEventListener('scroll', () => {
    if (!railRaf) railRaf = requestAnimationFrame(() => { railRaf = 0; updateRail(); });
    maybeLoadMore();
  });
  window.addEventListener('resize', updateRail);

  // ---- tabs ----

  document.querySelectorAll('nav button').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.tab = btn.dataset.tab;
      state.albumId = null;
      state.albumName = null;
      state.assets = [];
      document.querySelectorAll('nav button').forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      if (state.tab === 'search') {
        renderSearch();
      } else if (state.tab === 'albums') {
        renderAlbums();
      } else {
        loadStream();
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

  // ---- settings (slideshow intervals, screensaver sources/videos/info) ----

  const settingsSheet = $('#settingsSheet');
  const sourcesEl = $('#dreamSources');

  function setSettingsStatus(text, isError) {
    const el = $('#settingsStatus');
    el.textContent = text || '';
    el.classList.toggle('error', !!isError);
  }

  async function saveSettings(patch, okText) {
    setSettingsStatus('Saving…');
    try {
      await api('/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      setSettingsStatus(okText);
    } catch (e) {
      setSettingsStatus('Save failed', true);
    }
  }

  function collectSources() {
    const out = [];
    sourcesEl.querySelectorAll('input[data-kind]').forEach((cb) => {
      if (!cb.checked) return;
      if (cb.dataset.kind === 'album') out.push({ kind: 'album', id: cb.dataset.id, name: cb.dataset.name });
      else out.push({ kind: cb.dataset.kind });
    });
    return out;
  }

  function onSourcesChange() {
    const sources = collectSources();
    const summary = sources.length
      ? sources.map((s) => s.name || (s.kind === 'favorites' ? 'Favorites' : 'Timeline')).join(', ')
      : 'timeline (default)';
    saveSettings({ dreamSources: sources }, 'Screensaver sources: ' + summary + ' (next time it runs)');
  }

  function addSourceRow(kind, id, name, label, checked) {
    const l = document.createElement('label');
    l.className = 'chk src-row';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = checked;
    cb.dataset.kind = kind;
    if (id) cb.dataset.id = id;
    if (name) cb.dataset.name = name;
    cb.addEventListener('change', onSourcesChange);
    l.appendChild(cb);
    const span = document.createElement('span');
    span.textContent = label;
    l.appendChild(span);
    sourcesEl.appendChild(l);
  }

  async function openSettings() {
    settingsSheet.classList.remove('hidden');
    setSettingsStatus('');
    sourcesEl.innerHTML = '<p class="muted small" style="padding:0 .9rem">Loading…</p>';
    try {
      const [cfg, albums] = await Promise.all([api('/settings'), api('/albums')]);
      $('#viewerInterval').value = cfg.slideshowSeconds;
      $('#gridColumns').value = cfg.gridColumns || 7;
      $('#dreamInterval').value = cfg.dreamSeconds;
      $('#dreamVideos').checked = !!cfg.dreamIncludeVideos;
      $('#dreamInfo').checked = !!cfg.dreamShowInfo;
      const selected = {};
      (cfg.dreamSources || []).forEach((s) => {
        selected[s.kind === 'album' ? 'album:' + s.id : s.kind] = true;
      });
      sourcesEl.innerHTML = '';
      addSourceRow('timeline', null, null, 'Timeline (newest first)', !!selected.timeline);
      addSourceRow('favorites', null, null, 'Favorites', !!selected.favorites);
      if (albums.length) {
        const h = document.createElement('div');
        h.className = 'src-header';
        h.textContent = 'Albums';
        sourcesEl.appendChild(h);
        albums.forEach((al) => {
          addSourceRow('album', al.id, al.name, al.name + ' · ' + al.count, !!selected['album:' + al.id]);
        });
      }
    } catch (e) {
      setSettingsStatus('Could not load settings', true);
    }
  }

  $('#viewerInterval').addEventListener('change', () => {
    const v = parseInt($('#viewerInterval').value, 10);
    if (!(v >= 3 && v <= 120)) { setSettingsStatus('Viewing interval must be 3–120 s', true); return; }
    saveSettings({ slideshowSeconds: v }, 'Viewing slideshow: ' + v + ' s per photo');
  });

  $('#gridColumns').addEventListener('change', () => {
    const v = parseInt($('#gridColumns').value, 10);
    if (!(v >= 3 && v <= 12)) { setSettingsStatus('Grid columns must be 3–12', true); return; }
    saveSettings({ gridColumns: v }, 'TV photo grid: ' + v + ' columns wide');
  });

  $('#dreamInterval').addEventListener('change', () => {
    const v = parseInt($('#dreamInterval').value, 10);
    if (!(v >= 5 && v <= 300)) { setSettingsStatus('Screensaver interval must be 5–300 s', true); return; }
    saveSettings({ dreamSeconds: v }, 'Screensaver: ' + v + ' s per photo (next time it runs)');
  });

  $('#dreamVideos').addEventListener('change', () => {
    const on = $('#dreamVideos').checked;
    saveSettings(
      { dreamIncludeVideos: on },
      on ? 'Videos will play in the screensaver (next time it runs)'
         : 'Videos are excluded from the screensaver (next time it runs)'
    );
  });

  $('#dreamInfo').addEventListener('change', () => {
    const on = $('#dreamInfo').checked;
    saveSettings(
      { dreamShowInfo: on },
      on ? 'Screensaver will show photo info (next time it runs)'
         : 'Screensaver info hidden (next time it runs)'
    );
  });

  $('#btnSettings').addEventListener('click', openSettings);
  $('#settingsClose').addEventListener('click', () => settingsSheet.classList.add('hidden'));
  settingsSheet.addEventListener('click', (e) => {
    if (e.target === settingsSheet) settingsSheet.classList.add('hidden');
  });

  function syncControls() {
    const playing = !!(state.tv && state.tv.slideshow);
    $('#btnPlay').classList.toggle('on', playing);
    $('#btnPlay .ic-play').classList.toggle('hidden', playing);
    $('#btnPlay .ic-pause').classList.toggle('hidden', !playing);
    $('#btnShuffle').classList.toggle('on', !!(state.tv && state.tv.shuffle));
    document.querySelectorAll('.cell.current').forEach((c) => c.classList.remove('current'));
    if (state.tv && state.tv.assetId) {
      const el = mainEl.querySelector('.cell[data-id="' + state.tv.assetId + '"]');
      if (el) el.classList.add('current');
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
      $('#certBanner').classList.toggle('hidden', state.tv.certChanged !== true);
      syncControls();
    } catch (e) {
      $('#connState').classList.add('off');
    } finally {
      pollBusy = false;
    }
  }
  setInterval(pollState, 2000);

  // ---- certificate recovery ----

  async function openCertSheet() {
    $('#certStatus').textContent = 'Probing the server certificate…';
    $('#certSheet').classList.remove('hidden');
    try {
      const s = await api('/cert/reprobe', { method: 'POST' });
      renderCertCheck(s);
    } catch (e) {
      $('#certStatus').textContent = 'Could not probe the server: ' + e.message;
    }
  }

  function renderCertCheck(s) {
    if (s.error) {
      $('#certStatus').textContent = 'Probe failed: ' + s.error;
      return;
    }
    $('#certLeafFp').textContent = s.fingerprint || '?';
    $('#certSubjectLine').textContent =
      (s.subject ? 'subject: ' + s.subject + ' · ' : '') + (s.issuer || '');
    if (s.caFingerprint) {
      $('#certCaBlock').classList.remove('hidden');
      $('#certCaLine').textContent = s.caIssuer || '';
      $('#certCaFp').textContent = s.caFingerprint;
    } else {
      $('#certCaBlock').classList.add('hidden');
    }
    $('#certStatus').textContent = s.changed
      ? 'This certificate is not the one the TV currently trusts.'
      : 'This certificate already matches what the TV trusts.';
  }

  async function confirmCert() {
    $('#certConfirm').disabled = true;
    $('#certStatus').textContent = 'Confirming…';
    try {
      const r = await api('/cert/confirm', { method: 'POST' });
      if (!r.ok) {
        $('#certStatus').textContent = 'Failed: ' + (r.error || 'unknown error');
        return;
      }
      $('#certStatus').textContent = 'Certificate updated — reconnecting to your library…';
      setTimeout(() => {
        $('#certSheet').classList.add('hidden');
        loadStream();
        pollState();
      }, 1200);
    } catch (e) {
      $('#certStatus').textContent = 'Failed: ' + e.message;
    } finally {
      $('#certConfirm').disabled = false;
    }
  }

  $('#certBanner').addEventListener('click', openCertSheet);
  $('#certConfirm').addEventListener('click', confirmCert);
  $('#certCancel').addEventListener('click', () => $('#certSheet').classList.add('hidden'));

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
        if (s.caFingerprint) {
          $('#setupCa').classList.remove('hidden');
          $('#setupCaIssuer').textContent = s.caIssuer || '';
          $('#setupCaFp').textContent = s.caFingerprint;
        } else {
          $('#setupCa').classList.add('hidden');
        }
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
