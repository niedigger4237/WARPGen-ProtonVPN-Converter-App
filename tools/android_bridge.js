// Injected by the Android WebView only for the bundled top-level page.
if (window.AndroidHost) {
  const pending = new Map();
  let sequence = 0;
  AndroidHost.onmessage = ({data}) => {
    const message = JSON.parse(data);
    const call = pending.get(message.id);
    if (!call) return;
    pending.delete(message.id);
    if ('error' in message) call.reject(new Error(message.error));
    else call.resolve(message.result);
  };
  function invoke(method, args = {}) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      pending.set(id, {resolve, reject});
      try { AndroidHost.postMessage(JSON.stringify({id, method, args})); }
      catch (error) { pending.delete(id); reject(error); }
    });
  }
  window.warpBridge = {
    platform: 'android',
    async request(method, endpoint, body, token, proxy) {
      const headers = {'User-Agent': 'CloudflareWarp/1.0'};
      if (token) headers.Authorization = 'Bearer ' + token;
      const r = await invoke('request', {
        url: 'https://api.cloudflareclient.com/v0i1909051800/' + endpoint,
        method, headers, proxy: proxy || '', body: body ? JSON.stringify(body) : ''
      });
      if (r.status < 200 || r.status >= 300) throw new Error('HTTP ' + r.status + ': ' + r.data.slice(0, 160));
      return JSON.parse(r.data);
    },
    async saveFiles(files) {
      const args = {files: files.map(f => ({name: f.fileName, text: f.text}))};
      try { return await invoke('saveFiles', args); }
      catch { return invoke('shareFiles', args); }
    },
    dpi: {
      available: async () => (await invoke('dpiAvailable')).available,
      start: (args, port, notify, text) => invoke('dpiStart', {args, port, notify, text}),
      stop: () => invoke('dpiStop'),
      status: () => invoke('dpiStatus'),
      probe: (url, port, timeout) => invoke('dpiProbe', {url, port, timeout})
    }
  };
}
