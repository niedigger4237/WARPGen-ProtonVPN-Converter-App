
(() => {
  const D = __WARPDATA__;
  const $ = id => document.getElementById(id);
  const val = (id, fb = '') => { const e = $(id); return e.value.trim() || e.placeholder || fb; };
  const radio = name => document.querySelector(`input[name="${name}"]:checked`).value;
  const rnd = (a, b) => Math.floor(Math.random() * (b - a + 1)) + a;
  const pick = arr => arr[Math.floor(Math.random() * arr.length)];
  const toast = AppToast;

  const DNS = [
    ['cf', 'Cloudflare 1.1.1.1', '1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001'],
    ['google', 'Google 8.8.8.8', '8.8.8.8, 8.8.4.4, 2001:4860:4860::8888, 2001:4860:4860::8844'],
    ['malw', 'dns.malw.link', '95.216.204.218, 80.253.249.40, 2a01:4f9:c014:6dac::1, 2a12:bec4:1460:5b7::2'],
    ['xbox', 'xbox-dns.ru', '111.88.96.54, 111.88.96.55, 2a00:ab00:1233:26::50, 2a00:ab00:1233:26::51'],
    ['geohide', 'geohide.ru', '45.155.204.190, 37.230.192.51, 159.194.200.33, 193.233.112.67, 193.233.112.88'],
    ['comss', 'dns.comss.one', '83.220.169.155, 212.109.195.93, 195.133.25.16, 2a01:230:4:915::2, 2a01:230:4:306::2'],
  ];
  const SITE_NAMES = { youtube: 'YouTube', discord: 'Discord', whatsapp: 'WhatsApp', telegram: 'Telegram', twitter: 'Twitter', instagram: 'Instagram', facebook: 'Facebook', viber: 'Viber', itch: 'Itch.io', rutracker: 'RuTracker', rutor: 'Rutor', nnmclub: 'NNMClub', bestchange: 'BestChange', animego: 'AnimeGo', hdrezka: 'HDrezka' };
  const PREFIXES = ['162.159.192.', '162.159.195.', 'engage.cloudflareclient.com', '8.6.112.', '8.34.70.', '8.34.146.', '8.35.211.', '8.39.125.', '8.39.204.', '8.39.214.', '8.47.69.', '188.114.96.', '188.114.97.', '188.114.98.', '188.114.99.'];

  let account = null;   // {privKey, peer_pub, client_ipv4, client_ipv6}
  let seed = null;      // случайные значения текущей генерации
  let outputs = [];
  let busy = false;
  let renderId = 0;

  /* ---------- регистрация WARP ---------- */
  const API = 'https://api.cloudflareclient.com/v0i1909051800/';
  async function proxyValue() {
    if (!$('w-useProxy').checked) return null;
    if (radio('wpx') === 'dpi') return window.AppDpi ? await window.AppDpi.ensure() : (() => { throw new Error('ByeDPI недоступен'); })();
    const v = $('w-proxy').value.trim();
    if (!/^(https?|socks5h?|socks4a?|socks):\/\/[^\s/]+$/i.test(v)) throw new Error('Укажите прокси в формате socks5://хост:порт или http://хост:порт (можно user:pass@хост:порт)');
    return v;
  }
  async function apiCall(method, endpoint, body, token) {
    const proxy = await proxyValue();
    // Windows: запрос выполняет основной процесс Electron; Android: нативный плагин (прокси) или CapacitorHttp; иначе обычный fetch
    if (window.warpBridge) return window.warpBridge.request(method, endpoint, body || null, token || null, proxy);
    const headers = { 'User-Agent': 'CloudflareWarp/1.0', 'Content-Type': 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    const cap = window.Capacitor;
    if (proxy) {
      if (!(cap && cap.Plugins && cap.Plugins.WarpHttp)) throw new Error('Прокси работает только в приложении для Android и Windows');
      const r = await cap.Plugins.WarpHttp.request({ url: API + endpoint, method, headers, body: body ? JSON.stringify(body) : '', proxy });
      if (r.status < 200 || r.status >= 300) throw new Error(`HTTP ${r.status}: ${String(r.data).slice(0, 160)}`);
      try { return JSON.parse(r.data); } catch { throw new Error('Некорректный ответ сервера: ' + String(r.data).slice(0, 120)); }
    }
    const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), 20000);
    try {
      const r = await fetch(API + endpoint, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: ctl.signal });
      const text = await r.text();
      if (!r.ok) throw new Error(`HTTP ${r.status}: ${text.slice(0, 160)}`);
      try { return JSON.parse(text); } catch { throw new Error('Некорректный ответ сервера: ' + text.slice(0, 120)); }
    } finally { clearTimeout(tm); }
  }
  async function registerWarp() {
    const kp = X25519.generateKeyPair();
    const reg = await apiCall('POST', 'reg', { install_id: '', tos: new Date().toISOString(), key: kp.pub, fcm_token: '', type: 'ios', locale: 'en_US' });
    if (!reg.result || !reg.result.id) throw new Error('Не удалось зарегистрировать устройство');
    const en = await apiCall('PATCH', `reg/${reg.result.id}`, { warp_enabled: true }, reg.result.token);
    const c = en.result && en.result.config;
    if (!c || !c.peers || !c.peers[0]) throw new Error('Cloudflare не вернул конфигурацию');
    return { privKey: kp.priv, peer_pub: c.peers[0].public_key, client_ipv4: c.interface.addresses.v4, client_ipv6: c.interface.addresses.v6 };
  }

  /* ---------- параметры ---------- */
  function newSeed() {
    const prefix = pick(PREFIXES);
    return {
      port: pick(D.ports), prefix, last: rnd(1, 10), num: rnd(10, 99),
      domain: pick(D.domains), xray: pick(D.xray),
    };
  }
  function endpoint() {
    const m = $('w-epMode').value;
    if (m === 'engage') return 'engage.cloudflareclient.com:2408';
    if (m === 'custom') return $('w-ep').value.trim() || '162.159.192.1:2408';
    return seed.prefix === 'engage.cloudflareclient.com' ? `${seed.prefix}:${seed.port}` : `${seed.prefix}${seed.last}:${seed.port}`;
  }
  const dnsList = () => {
    let l = DNS.find(d => d[0] === $('w-dns').value)[2].split(',').map(s => s.trim());
    if (!$('w-ipv6').checked) l = l.filter(x => !x.includes(':'));
    return l;
  };
  const ka = () => $('w-useKa').checked ? val('w-ka', '25') : '';
  const mtu = () => val('w-mtu', '1280');
  function allowedIPs() {
    const v6 = $('w-ipv6').checked;
    if ($('w-nolan').checked) return v6 ? D.nolan : D.nolan.split(',').map(s => s.trim()).filter(x => !x.includes(':')).join(', ');
    const sel = Object.keys(D.sites).filter(k => $('w-site-' + k).checked);
    if (!sel.length) return v6 ? '0.0.0.0/0, ::/0' : '0.0.0.0/0';
    return sel.map(k => D.sites[k]).join(', ');
  }
  function awg3Pairs() {
    const out = [];
    if ($('w-awg3').checked) {
      [['cpa', 'ContentPaddingAddition', 'content-padding-addition'], ['rkat', 'RekeyAfterTime', 'rekey-after-time'], ['rt', 'RekeyTimeout', 'rekey-timeout'],
       ['rat', 'RejectAfterTime', 'reject-after-time'], ['kt', 'KeepaliveTimeout', 'keepalive-timeout'], ['mha', 'MaxHandshakeAttempts', 'max-handshake-attempts']]
        .forEach(([id, a, c]) => { const v = val('w-' + id); if (v && Number(v) !== 0) out.push([a, c, v]); });
    }
    if ($('w-awg31').checked) {
      if ($('w-trailers').checked) out.push(['RandomTrailers', 'random-trailers', 'on']);
      if ($('w-cookies').checked) out.push(['DisableCookies', 'disable-cookies', 'on']);
    }
    return out;
  }
  const jc = () => $('w-noJunk').checked ? 0 : 4;
  const addrs = () => $('w-ipv6').checked ? `${account.client_ipv4}, ${account.client_ipv6}` : account.client_ipv4;

  /* ---------- сборка ---------- */
  async function i1ForDomain(domain) {
    const v = await window.generateI1FromDomain(domain);
    if (!v) throw new Error('Не удалось сгенерировать I1 для домена ' + domain);
    return v;
  }
  async function buildAwg() {
    const variant = radio('wvar');
    let i1;
    if (variant === 'quic') i1 = D.awgVariants.quic;
    else if (variant === 'sip') i1 = D.awgVariants.sip;
    else i1 = 'I1 = ' + await i1ForDomain($('w-domain').value.trim() || seed.domain);
    const L = ['[Interface]', `PrivateKey = ${account.privKey}`, `Address = ${addrs()}`, `DNS = ${dnsList().join(', ')}`, `MTU = ${mtu()}`,
      'S1 = 0', 'S2 = 0', 'S3 = 0', 'S4 = 0', `Jc = ${jc()}`, 'Jmin = 40', 'Jmax = 70', 'H1 = 1', 'H2 = 2', 'H3 = 3', 'H4 = 4', i1];
    awg3Pairs().forEach(([a, , v]) => L.push(`${a} = ${v}`));
    L.push('', '[Peer]', `PublicKey = ${account.peer_pub}`, `AllowedIPs = ${allowedIPs()}`, `Endpoint = ${endpoint()}`);
    if (ka()) L.push(`PersistentKeepalive = ${ka()}`);
    return { fileName: `WARPv${{ quic: 1, domain: 2, sip: 3 }[variant]}_${seed.num}.conf`, text: L.join('\n') + '\n' };
  }
  function buildWireSock() {
    const L = ['[Interface]', `PrivateKey = ${account.privKey}`, `Address = ${addrs()}`, `DNS = ${dnsList().join(', ')}`, `MTU = ${mtu()}`,
      'S1 = 0', 'S2 = 0', 'S3 = 0', 'S4 = 0', `Jc = ${jc()}`, 'Jmin = 40', 'Jmax = 70', 'H1 = 1', 'H2 = 2', 'H3 = 3', 'H4 = 4', '',
      '# Protocol masking', `Id = ${$('w-domain').value.trim() || seed.domain}`, `Ip = ${$('w-wsIp').value}`, `Ib = ${$('w-wsIb').value}`,
      '', '[Peer]', `PublicKey = ${account.peer_pub}`, `AllowedIPs = ${allowedIPs()}`, `Endpoint = ${endpoint()}`];
    if (ka()) L.push(`PersistentKeepalive = ${ka()}`);
    return { fileName: `WARPw_${seed.num}.conf`, text: L.join('\n') + '\n' };
  }
  function buildClash() {
    const extra = awg3Pairs().map(([, c, v]) => `   ${c}: ${v === 'on' ? 'true' : v}`).join('\n');
    const v6 = $('w-ipv6').checked;
    const dns = dnsList();
    const y = ['warp-common: &warp-common', '  type: wireguard', `  ip: ${account.client_ipv4}`];
    if (v6) y.push(`  ipv6: ${account.client_ipv6}`);
    y.push(`  private-key: ${account.privKey}`, `  public-key: ${account.peer_pub}`, "  allowed-ips: ['0.0.0.0/0']", '  udp: true', `  mtu: ${mtu()}`);
    if (ka()) y.push(`  persistent-keepalive: ${ka()}`);
    y.push('  remote-dns-resolve: true', `  dns: [${dns.join(', ')}]`, '', 'proxies:');
    D.clash.forEach((is, n) => {
      y.push(`- name: "Стандартный ${n + 1}"`, '  <<: *warp-common', '  server: 162.159.192.1', '  port: 4500', '  amnezia-wg-option:', '   version: 3', `   jc: ${jc()}`, '   jmin: 40', '   jmax: 70');
      is.forEach((h, k) => y.push(`   i${k + 1}: ${h}`));
      if (extra) y.push(extra);
    });
    y.push('', 'proxy-groups:', '- name: WARP', '  type: select', '  proxies:', '    - "Стандартный 1"', '    - "Стандартный 2"', '    - "Стандартный 3"',
      "  url: 'http://speed.cloudflare.com/'", '  interval: 300', 'rules:', '- MATCH,WARP');
    return { fileName: `ClashWARP_${seed.num}.yaml`, text: y.join('\n') + '\n' };
  }
  async function buildXray() {
    const v6 = $('w-ipv6').checked;
    let hex = seed.xray;
    const dom = $('w-domain').value.trim();
    if (dom) { const m = (await i1ForDomain(dom)).match(/0x([0-9a-f]+)/i); if (m) hex = m[1]; }
    const sniff = { destOverride: ['http', 'tls'], enabled: true };
    const peer = { allowedIPs: v6 ? ['0.0.0.0/0', '::/0'] : ['0.0.0.0/0'], endpoint: endpoint() };
    if (ka()) peer.keepAlive = Number(ka());
    peer.publicKey = account.peer_pub;
    const noises = [{ delay: '1-2', packet: hex, type: 'hex' }];
    for (let i = 0; i < (jc() ? 8 : 0); i++) noises.push({ delay: '1-3', packet: '23-911', type: 'rand' });
    const cfg = {
      dns: { servers: dnsList() },
      inbounds: [
        { listen: '127.0.0.1', port: 10808, protocol: 'socks', settings: { auth: 'noauth', udp: true }, sniffing: sniff, tag: 'socks-in' },
        { listen: '127.0.0.1', port: 10809, protocol: 'http', settings: {}, sniffing: sniff, tag: 'http-in' },
      ],
      log: { loglevel: 'warning' }, meta: null,
      outbounds: [
        { protocol: 'wireguard', settings: { address: v6 ? [`${account.client_ipv4}/32`, `${account.client_ipv6}/128`] : [`${account.client_ipv4}/32`], mtu: Number(mtu()), peers: [peer], secretKey: account.privKey },
          streamSettings: { sockopt: { dialerProxy: 'noise-out' } }, tag: 'warp' },
        { protocol: 'freedom', settings: { domainStrategy: 'AsIs', noises }, tag: 'noise-out' },
      ],
      remarks: 'WARP',
      routing: { domainStrategy: 'IPIfNonMatch', rules: [{ outboundTag: 'noise-out', port: '53', type: 'field' }, { network: 'tcp,udp', outboundTag: 'warp', type: 'field' }] },
    };
    return { fileName: `XrayWARP_${seed.num}.json`, text: JSON.stringify(cfg, null, 4) + '\n' };
  }

  async function render() {
    if (!account || !seed) return;
    const my = ++renderId;
    try {
      const f = radio('wfmt');
      const res = f === 'awg' ? await buildAwg() : f === 'wiresock' ? buildWireSock() : f === 'clash' ? buildClash() : await buildXray();
      if (my !== renderId) return;
      outputs = [res];
      $('w-out').value = res.text;
      $('w-hint').textContent = '';
    } catch (e) { outputs = []; $('w-out').value = ''; $('w-hint').textContent = String(e.message || e); }
    $('w-save').disabled = $('w-copy').disabled = !outputs.length;
  }

  /* ---------- интерфейс ---------- */
  function updateVisibility() {
    const f = radio('wfmt');
    $('w-varBox').classList.toggle('hidden', f !== 'awg');
    $('w-domBox').classList.toggle('hidden', !(f === 'wiresock' || f === 'xray' || (f === 'awg' && radio('wvar') === 'domain')));
    $('w-wsBox').classList.toggle('hidden', f !== 'wiresock');
    $('w-awg3panel').classList.toggle('hidden', !(f === 'awg' || f === 'clash'));
    $('w-splitPanel').classList.toggle('hidden', !(f === 'awg' || f === 'wiresock'));
    $('w-awg3box').classList.toggle('hidden', !$('w-awg3').checked);
    $('w-awg31box').classList.toggle('hidden', !$('w-awg31').checked);
    $('w-ep').classList.toggle('hidden', $('w-epMode').value !== 'custom');
    $('w-proxyBox').classList.toggle('hidden', !$('w-useProxy').checked);
    const dpi = radio('wpx') === 'dpi';
    $('w-proxy').classList.toggle('hidden', dpi);
    $('w-dpiHint').classList.toggle('hidden', !dpi);
    if (dpi && window.AppDpi) $('w-dpiHint').textContent = 'Будет запущен встроенный ByeDPI: ' + window.AppDpi.describe() + '. Стратегию можно подобрать на вкладке ByeDPI.';
    const lan = $('w-nolan').checked;
    document.querySelectorAll('#w-sites input').forEach(i => { i.disabled = lan; if (lan) i.checked = false; });
  }
  function setStatus() {
    $('w-acc').textContent = account ? `Аккаунт получен · ${account.client_ipv4}` : 'Аккаунт ещё не создан';
  }
  async function generate() {
    if (busy) return;
    busy = true; $('w-gen').disabled = true; $('w-gen').textContent = 'Подождите…';
    try {
      if (!account) { account = await registerWarp(); setStatus(); }
      seed = newSeed();
      await render();
      toast('Конфиг готов');
    } catch (e) {
      const msg = String(e.message || e);
      const net = /Failed to fetch|NetworkError|Load failed|abort|timeout/i.test(msg);
      $('w-hint').textContent = 'Ошибка: ' + (net ? 'нет связи с Cloudflare. Проверьте интернет (регистрация WARP работает только в приложении для Android и Windows) и повторите через пару минут.' : msg + (/^Укажите|только в приложении|ByeDPI|Движок/.test(msg) ? '' : ' — подождите несколько минут и повторите.'));
      toast('Не удалось получить конфиг');
    } finally { busy = false; $('w-gen').disabled = false; $('w-gen').textContent = 'Сгенерировать'; }
  }

  function init() {
    $('w-dns').innerHTML = DNS.map(d => `<option value="${d[0]}">${d[1]}</option>`).join('');
    $('w-sites').innerHTML = Object.keys(D.sites).map(k => `<label><input type="checkbox" id="w-site-${k}"><span>${SITE_NAMES[k] || k}</span></label>`).join('');
    document.querySelectorAll('#view-warp input,#view-warp select').forEach(el => {
      const h = () => { updateVisibility(); render(); };
      el.addEventListener('input', h); el.addEventListener('change', h);
    });
    $('w-gen').onclick = generate;
    $('w-newacc').onclick = () => { account = null; seed = null; outputs = []; $('w-out').value = ''; $('w-save').disabled = $('w-copy').disabled = true; setStatus(); toast('Аккаунт сброшен — будет создан новый'); };
    $('w-rndAwg3').onclick = () => {
      const r = (a, b, c, d) => `${rnd(a, b)}-${rnd(c, d)}`;
      $('w-cpa').value = r(5, 49, 50, 110); $('w-mha').value = r(5, 24, 25, 40); $('w-kt').value = r(5, 10, 11, 25);
      $('w-rat').value = r(50, 99, 100, 200); $('w-rkat').value = r(50, 99, 100, 150); $('w-rt').value = r(3, 9, 10, 15); render();
    };
    $('w-copy').onclick = async () => {
      const t = $('w-out').value;
      try { await navigator.clipboard.writeText(t); } catch { $('w-out').readOnly = false; $('w-out').select(); document.execCommand('copy'); $('w-out').readOnly = true; }
      toast('Скопировано');
    };
    $('w-save').onclick = () => { if (outputs.length) AppSave(outputs, 'Конфиг WARP'); };
    updateVisibility();
  }
  window.AppDpiChanged = updateVisibility;
  init();
})();
