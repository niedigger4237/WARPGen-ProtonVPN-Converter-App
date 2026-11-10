const { contextBridge, ipcRenderer } = require('electron');
// Единственная «дырка» в сеть: регистрация WARP через API Cloudflare (запрос выполняет основной процесс)
contextBridge.exposeInMainWorld('warpBridge', {
<<<<<<< HEAD
  dpi: {
    available: () => ipcRenderer.invoke('dpi:available'),
    start: (args, port) => ipcRenderer.invoke('dpi:start', { args, port }),
    stop: () => ipcRenderer.invoke('dpi:stop'),
    probe: (url, port, timeout, round) => ipcRenderer.invoke('dpi:probe', { url, port, timeout, round }),
  },
=======
>>>>>>> origin/main
  saveFiles: files => ipcRenderer.invoke('app:save', files),
  request: (method, endpoint, body, token, proxy) => ipcRenderer.invoke('warp:request', { method, endpoint, body, token, proxy }),
});
