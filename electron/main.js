const { app, BrowserWindow, Menu, session, ipcMain, net } = require('electron');
const path = require('path');

const API = 'https://api.cloudflareclient.com/v0i1909051800/';

// Запросы к Cloudflare WARP API делаются отсюда, чтобы не упираться в CORS; наружу пускаем только этот хост
ipcMain.handle('warp:request', async (_e, { method, endpoint, body, token }) => {
  if (!['POST', 'PATCH'].includes(method) || !/^reg(\/[A-Za-z0-9-]+)?$/.test(endpoint)) throw new Error('Недопустимый запрос');
  const headers = { 'User-Agent': 'CloudflareWarp/1.0', 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await net.fetch(API + endpoint, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(20000) });
  const text = await r.text();
  if (!r.ok) throw new Error(`HTTP ${r.status}: ${text.slice(0, 160)}`);
  try { return JSON.parse(text); } catch { throw new Error('Некорректный ответ сервера: ' + text.slice(0, 120)); }
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
