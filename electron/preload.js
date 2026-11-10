const { contextBridge, ipcRenderer } = require('electron');
// Единственная «дырка» в сеть: регистрация WARP через API Cloudflare (запрос выполняет основной процесс)
contextBridge.exposeInMainWorld('warpBridge', {
  saveFiles: files => ipcRenderer.invoke('app:save', files),
  request: (method, endpoint, body, token) => ipcRenderer.invoke('warp:request', { method, endpoint, body, token }),
});
