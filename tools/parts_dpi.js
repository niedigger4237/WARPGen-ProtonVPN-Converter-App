(() => {
  const $ = id => document.getElementById(id);
  const toast = AppToast;

  const STRATS = [
    { id: 'none',   name: 'Без обхода (проверка)', args: '' },
    { id: 's1',     name: 'Split по SNI', args: '--split 1+s' },
    { id: 'd1',     name: 'Disorder по SNI', args: '--disorder 1+s' },
    { id: 'tlsrec', name: 'TLS-record split', args: '--tlsrec 1+s' },
    { id: 'sd',     name: 'Split + disorder', args: '--split 1+s --disorder 3+s' },
    { id: 'dtr',    name: 'Disorder + TLS-record', args: '--disorder 1 --tlsrec 1+s' },
    { id: 'multi',  name: 'Multi-split + TLS-record', args: '--split 1 --split 3+s --tlsrec 1+s' },
    { id: 'oob',    name: 'OOB по SNI', args: '--oob 1+s' },
    { id: 'doob',   name: 'Disorder OOB по SNI', args: '--disoob 1+s' },
    { id: 'oobtr',  name: 'OOB + TLS-record', args: '--oob 3 --tlsrec 3+s' },
    { id: 'f8',     name: 'Fake, TTL 8', args: '--fake -1 --ttl 8' },
    { id: 'f5',     name: 'Fake, TTL 5', args: '--fake -1 --ttl 5' },
    { id: 'f10tr',  name: 'Fake, TTL 10 + TLS-record', args: '--fake -1 --ttl 10 --tlsrec 1+s' },
    { id: 'df',     name: 'Disorder + Fake', args: '--disorder 1 --fake -1 --ttl 8' },
    { id: 'auto1',  name: 'Авто: disorder + TLS-record', args: '--disorder 1 --auto=torst --tlsrec 1+s' },
    { id: 'auto2',  name: 'Авто: fake (ssl_err)', args: '--fake -1 --ttl 10 --auto=ssl_err --fake -1 --ttl 5' },
    { id: 'md5',    name: 'Fake + md5sig (только Android)', args: '--fake -1 --md5sig', noWin: true },
    { id: 'md5oob', name: 'OOB + Fake + md5sig (только Android)', args: '--oob 1 --fake -1 --md5sig', noWin: true },
  ];
  const TARGETS = [
    { id: 'cf', name: 'Cloudflare API', url: 'https://api.cloudflareclient.com/', on: true },
    { id: 'yt', name: 'YouTube', url: 'https://www.youtube.com/' },
    { id: 'dc', name: 'Discord', url: 'https://discord.com/' },
    { id: 'tg', name: 'Telegram Web', url: 'https://web.telegram.org/' },
    { id: 'ig', name: 'Instagram', url: 'https://www.instagram.com/' },
    { id: 'x',  name: 'X (Twitter)', url: 'https://x.com/' },
  ];
  const BAD_FLAG = /^(-i|--ip|-p|--port|-I|--conn-ip|-D|--daemon|-w|--pidfile|-E|--transparent|-H|--hosts|-j|--ipset)(=|$)/;
  const OK_TOKEN = /^[-A-Za-z0-9_.,:+=@%]+$/;

  function backend() {
    if (window.warpBridge && window.warpBridge.dpi) {
      const d = window.warpBridge.dpi;
      return { win: true, available: () => d.available(), start: (a, p, n, t) => d.start(a, p, n, t), stop: () => d.stop(), status: () => d.status(), probe: (u, p, t, r) => d.probe(u, p, t, r) };
    }
    const c = window.Capacitor;
    if (c && c.isNativePlatform && c.isNativePlatform() && c.Plugins && c.Plugins.WarpHttp && c.Plugins.WarpHttp.dpiStart) {
      const w = c.Plugins.WarpHttp;
      return { win: false, available: async () => !!(await w.dpiAvailable()).available, start: (a, p, n, t) => w.dpiStart({ args: a, port: p, notify: !!n, text: t || '' }), stop: () => w.dpiStop(), status: () => w.dpiStatus(), probe: (u, p, t) => w.dpiProbe({ url: u, port: p, timeout: t }) };
    }
    return null;
  }
  const be = backend();

  let present = false, running = null, aborted = false, searching = false;
  const store = { get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch { return d; } }, set(k, v) { try { localStorage.setItem(k, v); } catch {} } };

  const list = () => STRATS.filter(s => !(s.noWin && be && be.win));
  const port = () => { const n = parseInt($('d-port').value, 10); return n >= 1024 && n <= 65535 ? n : 1080; };
  function parseArgs(str) {
    const t = str.trim() ? str.trim().split(/\s+/) : [];
    for (const x of t) {
      if (!OK_TOKEN.test(x)) throw new Error(`Недопустимый параметр: ${x}`);
      if (BAD_FLAG.test(x)) throw new Error(`Параметр ${x} задаётся приложением и не может быть изменён`);
    }
    return t;
  }
  const currentArgsStr = () => $('d-args').value.trim();

  function setStatus() {
    const st = $('d-status');
    if (!be) { st.textContent = 'ByeDPI работает только в приложении для Android и Windows. В браузере доступен только просмотр стратегий.'; }
    else if (!present) { st.textContent = 'Движок ByeDPI не вшит в эту сборку (не удалась сборка из исходников). Можно запустить ByeDPI отдельным приложением и указать его адрес (socks5://127.0.0.1:1080) как свой прокси на вкладке WARP.'; }
    else if (running) { st.textContent = '● Запущен'; }
    else { st.textContent = '○ Остановлен'; }
    const can = !!be && present;
    $('d-start').disabled = !can || searching;
    $('d-start').textContent = running ? 'Перезапустить' : 'Запустить';
    $('d-stop').disabled = !can || !running || searching;
    $('d-search').disabled = !can || searching;
    $('d-addr').textContent = running ? `Локальный прокси: socks5://127.0.0.1:${running.port}  ·  параметры: ${running.args || 'нет'}` : '';
  }

  async function start() {
    const args = parseArgs(currentArgsStr());
    const p = port();
    if (running) await be.stop();
    await be.start(args, p, $('d-notify').checked, `socks5://127.0.0.1:${p} · ${currentArgsStr() || 'без параметров'}`);
    running = { port: p, args: currentArgsStr() };
    store.set('dpi.port', String(p)); store.set('dpi.notify', $('d-notify').checked ? '1' : '0'); store.set('dpi.args', currentArgsStr());
    setStatus();
  }
  async function stop() { if (be) { try { await be.stop(); } catch {} } running = null; setStatus(); }

  /* ---------- автоподбор ---------- */
  const row = (name, args) => {
    const d = document.createElement('div'); d.className = 'dpi-row';
    const a = document.createElement('div'); a.className = 'dpi-name'; a.textContent = name;
    const b = document.createElement('div'); b.className = 'dpi-state'; b.textContent = '…';
    d.append(a, b); d.title = args || '(без параметров)'; $('d-res').appendChild(d); return { d, b };
  };
  async function search() {
    const targets = TARGETS.filter(t => $('d-t-' + t.id).checked);
    if (!targets.length) { toast('Выберите хотя бы один сайт'); return; }
    const timeout = parseInt($('d-timeout').value, 10);
    const p = port();
    const wasRunning = running;
    searching = true; aborted = false; setStatus();
    $('d-abort').classList.remove('hidden'); $('d-res').textContent = '';
    if (running) { try { await be.stop(); } catch {} running = null; }
    const results = [];
    const strats = list();
    try {
      for (let i = 0; i < strats.length && !aborted; i++) {
        const s = strats[i];
        $('d-progress').textContent = `Проверка ${i + 1} из ${strats.length}: ${s.name}`;
        const r = row(s.name, s.args);
        let ok = 0, ms = 0, failed = '';
        try {
          await be.start(s.args ? s.args.split(' ') : [], p, false, '');
          await new Promise(res => setTimeout(res, 200));
          const rs = await Promise.all(targets.map(t => be.probe(t.url, p, timeout, `${Date.now()}-${i}`).catch(e => ({ ok: false, error: String(e && e.message || e) }))));
          rs.forEach(x => { if (x && x.ok) { ok++; ms += x.ms || 0; } });
          if (!ok) r.d.title += '\n' + ((rs.find(x => x && x.error) || {}).error || '');
        } catch (e) { failed = String(e && e.message || e); }
        try { await be.stop(); } catch {}
        if (failed) { r.b.textContent = 'не запустилась'; r.d.classList.add('bad'); r.d.title += '\n' + failed; }
        else if (ok) { const avg = Math.round(ms / ok); r.b.textContent = `${ok}/${targets.length} · ${avg} мс`; r.d.classList.add(ok === targets.length ? 'good' : 'part'); results.push({ s, ok, avg, row: r }); }
        else { r.b.textContent = `0/${targets.length}`; r.d.classList.add('bad'); }
      }
    } finally {
      searching = false; $('d-abort').classList.add('hidden');
    }
    // лучший: максимум успехов, затем минимальная задержка; «без обхода» не предлагаем, если он не единственный рабочий
    const cand = results.slice().sort((a, b) => b.ok - a.ok || a.avg - b.avg);
    const base = results.find(x => x.s.id === 'none');
    if (aborted) $('d-progress').textContent = 'Подбор остановлен.';
    else if (!cand.length) $('d-progress').textContent = 'Ни одна стратегия не сработала. Проверьте интернет или попробуйте другой набор сайтов.';
    else {
      const best = cand.find(x => x.s.id !== 'none' && (!base || x.ok > base.ok || (x.ok === base.ok && x.avg < base.avg * 0.8))) || cand[0];
      best.row.d.classList.add('best');
      $('d-strat').value = best.s.id; $('d-args').value = best.s.args; saveStrategy();
      $('d-progress').textContent = best.s.id === 'none'
        ? 'Сайты доступны и без обхода — стратегия не нужна.'
        : `Лучшая стратегия: «${best.s.name}» (${best.ok}/${targets.length}). Она выбрана и сохранена.`;
    }
    if (wasRunning) { try { await start(); } catch (e) { toast(String(e.message || e)); } }
    setStatus();
  }

  function saveStrategy() { store.set('dpi.strat', $('d-strat').value); store.set('dpi.args', currentArgsStr()); if (running) setStatus(); updateHint(); }
  function updateHint() { if (window.AppDpiChanged) window.AppDpiChanged(); }

  function init() {
    $('d-strat').innerHTML = list().map(s => `<option value="${s.id}">${s.name}</option>`).join('') + '<option value="custom">Своя</option>';
    $('d-targets').innerHTML = TARGETS.map(t => `<label><input type="checkbox" id="d-t-${t.id}"${t.on ? ' checked' : ''}><span>${t.name}</span></label>`).join('');
    $('d-port').value = store.get('dpi.port', '');
    $('d-notify').checked = store.get('dpi.notify', '1') !== '0';
    $('d-notify').onchange = () => store.set('dpi.notify', $('d-notify').checked ? '1' : '0');
    // состояние мог изменить внешний «Стоп» (кнопка в уведомлении, трей) или аварийное завершение процесса
    setInterval(async () => { if (running && be && !searching) { try { const st = await be.status(); if (st && !st.running) { running = null; setStatus(); } } catch {} } }, 3000);
    const sid = store.get('dpi.strat', 's1'); const known = list().some(s => s.id === sid);
    $('d-strat').value = known || sid === 'custom' ? sid : 's1';
    const saved = store.get('dpi.args', null);
    $('d-args').value = saved !== null && $('d-strat').value === 'custom' ? saved : (list().find(s => s.id === $('d-strat').value) || { args: '' }).args;
    $('d-strat').onchange = () => { const s = list().find(x => x.id === $('d-strat').value); if (s) $('d-args').value = s.args; saveStrategy(); };
    $('d-args').oninput = () => { const m = list().find(s => s.args === currentArgsStr()); $('d-strat').value = m ? m.id : 'custom'; saveStrategy(); };
    $('d-start').onclick = async () => { try { await start(); toast('ByeDPI запущен'); } catch (e) { toast('Не удалось запустить: ' + (e.message || e)); setStatus(); } };
    $('d-stop').onclick = async () => { await stop(); toast('ByeDPI остановлен'); };
    $('d-search').onclick = () => search().catch(e => { searching = false; $('d-abort').classList.add('hidden'); $('d-progress').textContent = 'Ошибка: ' + (e.message || e); setStatus(); });
    $('d-abort').onclick = () => { aborted = true; };
    (async () => { try { present = !!be && await be.available(); } catch { present = false; } setStatus(); })();
    setStatus();
  }
  init();

  /* ---------- API для вкладки WARP ---------- */
  window.AppDpi = {
    describe() { const s = list().find(x => x.id === $('d-strat').value); return (s ? s.name : 'своя стратегия') + (currentArgsStr() ? ` (${currentArgsStr()})` : ''); },
    async ensure() {
      if (!be) throw new Error('ByeDPI доступен только в приложении для Android и Windows');
      if (!present) throw new Error('Движок ByeDPI не вшит в эту сборку');
      if (searching) throw new Error('Идёт подбор стратегии — дождитесь окончания');
      const want = currentArgsStr();
      if (!running || running.args !== want) await start();
      return `socks5://127.0.0.1:${running.port}`;
    },
  };
})();
