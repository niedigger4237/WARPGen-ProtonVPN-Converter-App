const { app, BrowserWindow, Menu, session, ipcMain, net, dialog, shell } = require('electron');
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

// Сохранение конфигов на диск: один файл — диалог «Сохранить как», несколько — выбор папки
ipcMain.handle('app:save', async (e, files) => {
  if (!Array.isArray(files) || !files.length || files.length > 500) throw new Error('Нет файлов');
  const win = BrowserWindow.fromWebContents(e.sender);
  const safe = n => path.basename(String(n)).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_') || 'config.txt';
  const dl = app.getPath('downloads');
  let dir, target;
  if (files.length === 1) {
    const r = await dialog.showSaveDialog(win, { defaultPath: path.join(dl, safe(files[0].fileName)) });
    if (r.canceled || !r.filePath) return { canceled: true };
    fs.writeFileSync(r.filePath, String(files[0].text), 'utf8');
    target = r.filePath; shell.showItemInFolder(target);
    return { path: target, count: 1 };
  }
  const r = await dialog.showOpenDialog(win, { title: 'Папка для сохранения конфигов', defaultPath: dl, properties: ['openDirectory', 'createDirectory'] });
  if (r.canceled || !r.filePaths[0]) return { canceled: true };
  dir = r.filePaths[0];
  for (const f of files) fs.writeFileSync(path.join(dir, safe(f.fileName)), String(f.text), 'utf8');
  shell.openPath(dir);
  return { path: dir, count: files.length };
});

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
  win.loadFile(path.join(__dirname, '..', 'www', 'index.html'));
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
