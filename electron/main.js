const { app, BrowserWindow, Menu, session, ipcMain, net, dialog, shell, Notification, Tray, nativeImage } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const API = 'https://api.cloudflareclient.com/v0i1909051800/';

// Запросы к Cloudflare WARP API делаются отсюда, чтобы не упираться в CORS; наружу пускаем только этот хост.
// Если указан прокси (http/https/socks5), запрос идёт через отдельную сессию с этим прокси.
function parseProxy(str) {
  const m = /^(https?|socks5h?|socks4a?|socks):\/\/(?:([^:@\/]*)(?::([^@\/]*))?@)?([^\s/:@]+|\[[0-9a-f:]+\])(?::(\d{1,5}))?$/i.exec(String(str).trim());
  if (!m) throw new Error('Неверный адрес прокси');
  let scheme = m[1].toLowerCase();
  if (scheme === 'socks' || scheme === 'socks5h') scheme = 'socks5';
  if (scheme === 'socks4a') scheme = 'socks4';
  const port = m[5] || (scheme.startsWith('socks') ? '1080' : '8080');
  return { rules: `${scheme}://${m[4]}:${port}`, user: m[2] ? decodeURIComponent(m[2]) : '', pass: m[3] ? decodeURIComponent(m[3]) : '' };
}

function httpRequest(ses, method, url, headers, body, auth) {
  return new Promise((resolve, reject) => {
    const req = net.request({ method, url, session: ses, useSessionCookies: false });
    for (const [k, v] of Object.entries(headers)) req.setHeader(k, v);
    const timer = setTimeout(() => { req.abort(); reject(new Error('Таймаут запроса (проверьте прокси и интернет)')); }, 25000);
    req.on('login', (info, cb) => { if (info.isProxy && auth && auth.user) cb(auth.user, auth.pass); else cb(); });
    req.on('response', res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => { clearTimeout(timer); resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }); });
      res.on('error', e => { clearTimeout(timer); reject(e); });
    });
    req.on('error', e => { clearTimeout(timer); reject(e); });
    if (body) req.write(body);
    req.end();
  });
}

ipcMain.handle('warp:request', async (_e, { method, endpoint, body, token, proxy }) => {
  if (!['POST', 'PATCH'].includes(method) || !/^reg(\/[A-Za-z0-9-]+)?$/.test(endpoint)) throw new Error('Недопустимый запрос');
  const headers = { 'User-Agent': 'CloudflareWarp/1.0', 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  let ses = session.defaultSession, auth = null;
  if (proxy) {
    const p = parseProxy(proxy);
    ses = session.fromPartition('warp-proxy');
    await ses.setProxy({ proxyRules: p.rules });
    auth = p;
  }
  const r = await httpRequest(ses, method, API + endpoint, headers, body ? JSON.stringify(body) : null, auth);
  if (r.status < 200 || r.status >= 300) throw new Error(`HTTP ${r.status}: ${r.text.slice(0, 160)}`);
  try { return JSON.parse(r.text); } catch { throw new Error('Некорректный ответ сервера: ' + r.text.slice(0, 120)); }
});

// Запоминаем последнюю папку сохранения
const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');
const loadSettings = () => { try { return JSON.parse(fs.readFileSync(settingsFile(), 'utf8')); } catch { return {}; } };
const saveSettings = o => { try { fs.writeFileSync(settingsFile(), JSON.stringify({ ...loadSettings(), ...o })); } catch {} };

// Сохранение конфигов на диск: один файл — диалог «Сохранить как», несколько — выбор папки
ipcMain.handle('app:save', async (e, files) => {
  if (!Array.isArray(files) || !files.length || files.length > 500) throw new Error('Нет файлов');
  const win = BrowserWindow.fromWebContents(e.sender);
  const safe = n => path.basename(String(n)).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_') || 'config.txt';
  let dl = app.getPath('downloads');
  const last = loadSettings().lastDir;
  if (last && fs.existsSync(last)) dl = last;
  let dir, target;
  if (files.length === 1) {
    const r = await dialog.showSaveDialog(win, { defaultPath: path.join(dl, safe(files[0].fileName)) });
    if (r.canceled || !r.filePath) return { canceled: true };
    fs.writeFileSync(r.filePath, String(files[0].text), 'utf8');
    target = r.filePath; saveSettings({ lastDir: path.dirname(target) }); shell.showItemInFolder(target);
    return { path: target, count: 1 };
  }
  const r = await dialog.showOpenDialog(win, { title: 'Папка для сохранения конфигов', defaultPath: dl, properties: ['openDirectory', 'createDirectory'] });
  if (r.canceled || !r.filePaths[0]) return { canceled: true };
  dir = r.filePaths[0]; saveSettings({ lastDir: dir });
  for (const f of files) fs.writeFileSync(path.join(dir, safe(f.fileName)), String(f.text), 'utf8');
  shell.openPath(dir);
  return { path: dir, count: files.length };
});


/* ---------- ByeDPI (ciadpi.exe): локальный SOCKS5-прокси ---------- */
const BAD_FLAG = /^(-i|--ip|-p|--port|-I|--conn-ip|-D|--daemon|-w|--pidfile|-E|--transparent|-H|--hosts|-j|--ipset)(=|$)/;
const OK_TOKEN = /^[-A-Za-z0-9_.,:+=@%]+$/;
const dpiExe = () => app.isPackaged ? path.join(process.resourcesPath, 'byedpi', 'ciadpi.exe') : path.join(__dirname, '..', 'byedpi-bin', 'ciadpi.exe');
let dpiProc = null, tray = null, mainWin = null;
const iconPath = () => path.join(__dirname, '..', 'assets', 'icon.png');

function notify(title, body) { try { if (Notification.isSupported()) new Notification({ title, body, icon: iconPath(), silent: true }).show(); } catch {} }
function hideTray() { if (tray) { try { tray.destroy(); } catch {} tray = null; } }
function showTray(text) {
  hideTray();
  try {
    tray = new Tray(nativeImage.createFromPath(iconPath()).resize({ width: 16, height: 16 }));
    tray.setToolTip('ByeDPI запущен\n' + text);
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Открыть окно', click: () => { if (mainWin) { mainWin.show(); mainWin.focus(); } } },
      { label: 'Остановить ByeDPI', click: async () => { await dpiStop(); notify('ByeDPI остановлен', 'Прокси выключен'); } },
      { type: 'separator' },
      { label: 'Выход', click: () => app.quit() },
    ]));
    tray.on('click', () => { if (mainWin) { mainWin.show(); mainWin.focus(); } });
  } catch {}
}

function dpiStop() {
  hideTray();
  if (dpiProc) { const p = dpiProc; dpiProc = null; try { p.kill(); } catch {} }
  return new Promise(r => setTimeout(r, 120));
}
ipcMain.handle('dpi:available', () => fs.existsSync(dpiExe()));
ipcMain.handle('dpi:stop', () => dpiStop());
ipcMain.handle('dpi:status', () => ({ running: !!dpiProc }));
ipcMain.handle('dpi:start', async (_e, { args, port, notify: wantNotify, text }) => {
  if (!fs.existsSync(dpiExe())) throw new Error('Движок ByeDPI не найден в сборке');
  if (!Array.isArray(args) || args.length > 60 || !args.every(a => typeof a === 'string' && OK_TOKEN.test(a) && !BAD_FLAG.test(a))) throw new Error('Недопустимые параметры');
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Недопустимый порт');
  await dpiStop();
  const child = spawn(dpiExe(), ['-i', '127.0.0.1', '-p', String(port), ...args], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '', exited = null;
  const add = d => { if (out.length < 600) out += d.toString(); };
  child.stdout.on('data', add); child.stderr.on('data', add);
  child.on('exit', code => { exited = code; if (dpiProc === child) { dpiProc = null; hideTray(); notify('ByeDPI остановлен', `Процесс завершился (код ${code})`); } });
  child.on('error', e => { exited = -1; out += String(e.message || e); });
  dpiProc = child;
  await new Promise(r => setTimeout(r, 450));
  if (exited !== null) { dpiProc = null; throw new Error(`ByeDPI завершился (код ${exited}): ${out.trim().slice(0, 200) || 'порт занят или неверные параметры'}`); }
  if (wantNotify) { const t = String(text || `socks5://127.0.0.1:${port}`).slice(0, 200); showTray(t); notify('ByeDPI запущен', t); }
  return { running: true };
});
// Проверка доступности сайта через локальный SOCKS5: достаточно получить любой HTTP-ответ по HTTPS.
// Каждая проверка идёт в своей сессии: иначе closeAllConnections() первой завершившейся проверки обрывала бы параллельные.
let probeSeq = 0;
ipcMain.handle('dpi:probe', async (_e, { url, port, timeout }) => {
  if (!/^https:\/\/[a-z0-9.-]+\/$/i.test(url) || !Number.isInteger(port)) throw new Error('Недопустимый запрос');
  const ses = session.fromPartition(`dpi-${port}-${++probeSeq}`);
  await ses.setProxy({ proxyRules: `socks5://127.0.0.1:${port}` });
  const t0 = Date.now();
  return new Promise(resolve => {
    let done = false;
    const finish = v => { if (!done) { done = true; clearTimeout(timer); try { ses.closeAllConnections(); } catch {} resolve(v); } };
    const req = net.request({ method: 'GET', url, session: ses, useSessionCookies: false });
    req.setHeader('User-Agent', 'Mozilla/5.0');
    const timer = setTimeout(() => { try { req.abort(); } catch {} finish({ ok: false, error: 'таймаут' }); }, Math.min(Math.max(timeout | 0, 1000), 20000));
    req.on('response', res => { const ms = Date.now() - t0; res.on('data', () => {}); try { req.abort(); } catch {} finish({ ok: true, ms, status: res.statusCode }); });
    req.on('error', e => finish({ ok: false, error: String(e.message || e) }));
    req.end();
  });
});
app.on('before-quit', () => { hideTray(); if (dpiProc) { try { dpiProc.kill(); } catch {} } });

function createWindow() {
  const win = new BrowserWindow({
    width: 1000, height: 800, minWidth: 380, minHeight: 600,
    title: 'WARP + Proton AWG',
    icon: path.join(__dirname, '..', 'assets', 'icon.png'),
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false, preload: path.join(__dirname, 'preload.js') }
  });
  Menu.setApplicationMenu(null);
  // Окно ничего не грузит из сети: только локальные файлы (и служебный хост WARP API для основного процесса)
  session.defaultSession.webRequest.onBeforeRequest((d, cb) =>
    cb({ cancel: !(/^(file|blob|data|devtools):/i.test(d.url) || d.url.startsWith(API)) }));
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) e.preventDefault(); });
  mainWin = win;
  win.loadFile(path.join(__dirname, '..', 'www', 'index.html'));
}

app.setAppUserModelId('com.local.protonawg');
app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
