const { contextBridge, ipcRenderer } = require('electron');
// Единственная «дырка» в сеть: регистрация WARP через API Cloudflare (запрос выполняет основной процесс)
contextBridge.exposeInMainWorld('warpBridge', {
  dpi: {
    available: () => ipcRenderer.invoke('dpi:available'),
    start: (args, port, notify, text) => ipcRenderer.invoke('dpi:start', { args, port, notify: !!notify, text }),
    status: () => ipcRenderer.invoke('dpi:status'),
    stop: () => ipcRenderer.invoke('dpi:stop'),
    probe: (url, port, timeout, round) => ipcRenderer.invoke('dpi:probe', { url, port, timeout, round }),
  },
  saveFiles: files => ipcRenderer.invoke('app:save', files),
  request: (method, endpoint, body, token, proxy) => ipcRenderer.invoke('warp:request', { method, endpoint, body, token, proxy }),
});
