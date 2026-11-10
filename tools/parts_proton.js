"use strict";
const I1_PRESETS = __I1__;
const DOMAINS = __DOMAINS__;
const NOLAN_IPS = "__NOLAN__";
const PORTS = [80, 88, 443, 500, 1194, 1224, 4500, 4569, 5060, 51820];
const COUNTRY = {JP:'🇯🇵',US:'🇺🇸',NL:'🇳🇱',DE:'🇩🇪',FR:'🇫🇷',GB:'🇬🇧',CA:'🇨🇦',AU:'🇦🇺',RO:'🇷🇴',MX:'🇲🇽',NO:'🇳🇴',SG:'🇸🇬',CH:'🇨🇭',PL:'🇵🇱'};
const AWG_KEYS = ['jc','jmin','jmax','s1','s2','h1','h2','h3','h4'];

const $ = id => document.getElementById(id);
const val = (id, fb='') => { const e=$(id); return (e.value.trim() || e.placeholder || fb); };
const radio = name => document.querySelector(`input[name="${name}"]:checked`).value;
const rnd = (a,b) => Math.floor(Math.random()*(b-a+1))+a;
const pick = arr => arr[Math.floor(Math.random()*arr.length)];

let sources = [];   // {name, text}
let proxies = [];   // parsed
let outputs = [];   // [{fileName, text}]

/* ---------- parsing ---------- */
function parseWG(text){
  const cfg = {iface:{awg:{}}, peers:[]};
  let sec = null, pi = -1;
  const lines = text.split(/\r?\n/);
  for (let i=0;i<lines.length;i++){
    const line = lines[i].trim();
    if (!line) continue;
    if (line.startsWith('[') && line.endsWith(']')){
      sec = line.slice(1,-1).toLowerCase();
      if (sec==='peer'){
        pi++; cfg.peers.push({awg:{}});
        const nxt = (lines[i+1]||'').trim().match(/^#\s*(.+)/);
        if (nxt){ cfg.peers[pi].name = nxt[1].trim(); i++; }
      }
      continue;
    }
    if (line.startsWith('#')){
      // Комментарий с именем сервера перед/внутри секции
      if (sec==='interface' && !cfg.iface.comment) cfg.iface.comment = line.replace(/^#\s*/,'');
      continue;
    }
    const eq = line.indexOf('='); if (eq<0) continue;
    const k = line.slice(0,eq).trim().toLowerCase();
    const v = line.slice(eq+1).trim();
    if (sec==='interface'){ AWG_KEYS.includes(k) ? cfg.iface.awg[k]=v : cfg.iface[k]=v; }
    else if (sec==='peer' && pi>=0){ AWG_KEYS.includes(k) ? cfg.peers[pi].awg[k]=v : cfg.peers[pi][k]=v; }
  }
  if (cfg.peers.length && !cfg.peers[0].name){
    const m = text.match(/#\s*([A-Z]{2}-FREE#?\d+)/);
    if (m) cfg.peers[0].name = m[1];
  }
  return cfg;
}

function junkDefaults(){
  const m = radio('junk');
  let jc, jmin, jmax;
  if (m==='1'){ jc=3; jmin=1; jmax=3; }
  else if (m==='2'){ jc=30; jmin=10; jmax=30; }
  else {
    jc = parseInt($('jc').value) || 128;
    jmin = parseInt($('jmin').value) || 1279;
    jmax = parseInt($('jmax').value) || 1280;
  }
  if (jmax <= jmin) jmax = jmin + 1;
  return {jc, jmin, jmax, s1:0, s2:0, h1:1, h2:2, h3:3, h4:4};
}

function toProxy(cfg, fileName){
  const I = cfg.iface, P = cfg.peers[0];
  if (!P) throw new Error('нет секции [Peer]');
  for (const [obj,k,label] of [[I,'privatekey','PrivateKey'],[I,'address','Address'],[P,'publickey','PublicKey'],[P,'endpoint','Endpoint'],[P,'allowedips','AllowedIPs']])
    if (!obj[k]) throw new Error(`нет поля ${label}`);
  const def = junkDefaults();
  const awg = {};
  for (const k of AWG_KEYS) awg[k] = I.awg[k] || P.awg[k] || def[k];
  if ($('noJunk').checked) awg.jc = 0;   // без мусорных пакетов
  const orig = P.name || fileName.replace(/\.conf$/i,'');
  let name = orig.replace(/FREE#?/g,'').replace(/-$/,'');
  const fm = name.match(/^([A-Z]{2})[-_]/);
  if (fm && COUNTRY[fm[1]]) name = name.replace(fm[1], `${COUNTRY[fm[1]]} ${fm[1]}`);
  const addrs = I.address.split(',').map(s=>s.trim());
  const ep = P.endpoint.trim();
  const host = ep.startsWith('[') ? ep.slice(1, ep.indexOf(']')) : ep.slice(0, ep.lastIndexOf(':'));
  return {
    name, orig, host,
    priv: I.privatekey, pub: P.publickey, psk: P.presharedkey || '',
    address: I.address, ipv4: addrs.find(a=>!a.includes(':'))||'', ipv6: addrs.find(a=>a.includes(':'))||'',
    dns: I.dns ? I.dns.split(',').map(s=>s.trim()) : [],
    allowed: P.allowedips.split(',').map(s=>s.trim()),
    awg
  };
}

/* ---------- generation ---------- */
function opts(){
  const kaOn = $('useKa').checked;
  return {
    fmt: radio('fmt'),
    port: document.querySelector('input[name="port"]:checked').value,
    mtu: val('mtu','1420'),
    ka: kaOn ? val('ka','25') : '',
    nolan: $('nolan').checked && !['clash','xray'].includes(radio('fmt')),
    awg3: $('awg3').checked,
    awg31: $('awg31').checked,
    i: [1,2,3,4,5].map(n=>$('i'+n).value.trim()),
  };
}
function awg3Values(){
  return {cpa:val('cpa'), rkat:val('rkat'), rt:val('rt'), rat:val('rat'), kt:val('kt'), mha:val('mha')};
}

function buildConf(p, o){
  const L = [];
  L.push(`# ${p.orig || 'Безымянный конфиг'}`, '[Interface]', `PrivateKey = ${p.priv}`, `Address = ${p.address}`);
  if (p.dns.length) L.push(`DNS = ${p.dns.join(', ')}`);
  L.push(`MTU = ${o.mtu}`);
  const a = p.awg;
  L.push('S1 = 0','S2 = 0',`Jc = ${a.jc}`,`Jmin = ${a.jmin}`,`Jmax = ${a.jmax}`,`H1 = ${a.h1}`,`H2 = ${a.h2}`,`H3 = ${a.h3}`,`H4 = ${a.h4}`);
  if (o.fmt==='awg'){
    L.push(`I1 = ${o.i[0] || I1_PRESETS[0]}`);
    o.i.slice(1).forEach((v,n)=>{ if (v) L.push(`I${n+2} = ${v}`); });
  }
  if (o.fmt==='wiresock'){
    L.push(`Id = ${val('wsId','apteka.ru')}`, `Ip = ${$('wsIp').value}`, `Ib = ${$('wsIb').value}`);
  }
  if (o.awg3){
    const v = awg3Values();
    if (v.cpa)  L.push(`ContentPaddingAddition = ${v.cpa}`);
    if (v.rkat) L.push(`RekeyAfterTime = ${v.rkat}`);
    if (v.rt)   L.push(`RekeyTimeout = ${v.rt}`);
    if (v.rat)  L.push(`RejectAfterTime = ${v.rat}`);
    if (v.kt)   L.push(`KeepaliveTimeout = ${v.kt}`);
    if (v.mha)  L.push(`MaxHandshakeAttempts = ${v.mha}`);
  }
  if (o.awg31) L.push('DisableCookies = on');
  L.push('', '[Peer]', `PublicKey = ${p.pub}`);
  if (p.psk) L.push(`PresharedKey = ${p.psk}`);
  L.push(`AllowedIPs = ${o.nolan ? NOLAN_IPS : p.allowed.join(', ')}`);
  L.push(`Endpoint = ${p.host.includes(':') ? '['+p.host+']' : p.host}:${o.port}`);
  if (o.ka) L.push(`PersistentKeepalive = ${o.ka}`);
  return L.join('\n') + '\n';
}

function buildClash(list, o){
  const y = ['proxies:'];
  for (const p of list){
    y.push(`- name: ${p.name}`, '  type: wireguard', `  server: ${p.host}`, `  port: ${o.port}`, `  ip: ${p.ipv4}`);
    if (p.ipv6) y.push(`  ipv6: ${p.ipv6}`);
    y.push(`  private-key: ${p.priv}`, `  public-key: ${p.pub}`, `  allowed-ips: [${p.allowed.map(x=>`'${x}'`).join(', ')}]`);
    if (p.psk) y.push(`  pre-shared-key: ${p.psk}`);
    if (o.ka) y.push(`  persistent-keepalive: ${o.ka}`);
    y.push('  udp: true', `  mtu: ${o.mtu}`, '  remote-dns-resolve: true', `  dns: [${p.dns.join(', ')}]`, '  amnezia-wg-option:', '    version: 3');
    for (const [k,v] of Object.entries(p.awg)) y.push(`    ${k}: ${v}`);
    y.push(`    i1: ${o.i[0] || I1_PRESETS[0]}`);
    o.i.slice(1).forEach((v,n)=>{ if (v) y.push(`    i${n+2}: ${v}`); });
    if (o.awg3){
      const v = awg3Values();
      if (v.cpa)  y.push(`    content-padding-addition: ${v.cpa}`);
      if (v.rkat) y.push(`    rekey-after-time: ${v.rkat}`);
      if (v.rt)   y.push(`    rekey-timeout: ${v.rt}`);
      if (v.rat)  y.push(`    reject-after-time: ${v.rat}`);
      if (v.kt)   y.push(`    keepalive-timeout: ${v.kt}`);
      if (v.mha)  y.push(`    max-handshake-attempts: ${v.mha}`);
    }
    if (o.awg31) y.push('    disable-cookies: true');
    y.push('');
  }
  const proton = [], other = [];
  for (const p of list) (/(^|[_-])([A-Z]{2})([-_]FREE)?([#_-]|$)/i.test(p.orig) ? proton : other).push(p.name);
  y.push('proxy-groups:');
  const grp = (n, arr) => { if (arr.length) y.push(`- name: ${n}`, '  type: select', '  proxies:', ...arr.map(x=>`    - ${x}`), "  url: 'http://speed.cloudflare.com/'", '  unified-delay: true', '  interval: 300'); };
  grp('Proton', proton); grp('Other', other);
  return y.join('\n') + '\n';
}


function buildXray(p, o){
  const hex = ((o.i[0] || I1_PRESETS[0]).match(/0x([0-9a-fA-F]+)/) || [,''])[1];
  const noises = [{delay:'1-2', packet:hex, type:'hex'}];
  for (let n=0; n<(parseInt(p.awg.jc)||0); n++) noises.push({delay:'1-3', packet:`${p.awg.jmin}-${p.awg.jmax}`, type:'rand'});
  const sniff = {destOverride:['http','tls'], enabled:true};
  const peer = {allowedIPs: p.allowed};
  if (p.psk) peer.preSharedKey = p.psk;
  peer.endpoint = `${p.host.includes(':') ? '['+p.host+']' : p.host}:${o.port}`;
  if (o.ka) peer.keepAlive = Number(o.ka);
  peer.publicKey = p.pub;
  const cfg = {
    dns: {servers: p.dns.length ? p.dns : ['1.1.1.1','1.0.0.1']},
    inbounds: [
      {listen:'127.0.0.1', port:10808, protocol:'socks', settings:{auth:'noauth', udp:true}, sniffing:sniff, tag:'socks-in'},
      {listen:'127.0.0.1', port:10809, protocol:'http', settings:{}, sniffing:sniff, tag:'http-in'},
    ],
    log: {loglevel:'warning'}, meta: null,
    outbounds: [
      {protocol:'wireguard', settings:{address: p.address.split(',').map(a=>a.trim()), mtu:Number(o.mtu), peers:[peer], secretKey:p.priv},
       streamSettings:{sockopt:{dialerProxy:'noise-out'}}, tag:'proton'},
      {protocol:'freedom', settings:{domainStrategy:'AsIs', noises}, tag:'noise-out'},
    ],
    remarks: (p.orig||'Proton').replace(/-FREE/g,'').replace(/[^a-z0-9]/gi,'_'),
    routing: {domainStrategy:'AsIs', rules:[{network:'tcp,udp', outboundTag:'proton', type:'field'}]},
  };
  return JSON.stringify(cfg, null, 4) + '\n';
}

function fileNameFor(p, idx, ext='conf'){
  const base = (p.orig||'').replace(/-FREE/g,'').replace(/[^a-z0-9]/gi,'_').replace(/_+/g,'_').replace(/^_|_$/g,'');
  return base ? `${base}.${ext}` : `config_${idx+1}.${ext}`;
}

function regenerate(){
  proxies = []; outputs = [];
  const errs = [];
  sources.forEach(s=>{
    try { proxies.push(toProxy(parseWG(s.text), s.name)); }
    catch(e){ errs.push(`${s.name}: ${e.message}`); }
  });
  const o = opts();
  if (proxies.length){
    if (o.fmt==='clash'){
      outputs = [{fileName:'clash-config.yaml', text: buildClash(proxies, o)}];
    } else {
      const used = new Set();
      outputs = proxies.map((p,i)=>{
        const ext = o.fmt==='xray' ? 'json' : 'conf';
        let n = fileNameFor(p,i,ext); while (used.has(n)) n = n.replace(/(\.\w+)$/, `_${i}$1`); used.add(n);
        return {fileName:n, text: o.fmt==='xray' ? buildXray(p,o) : buildConf(p,o)};
      });
    }
  }
  $('out').value = outputs.length === 1 ? outputs[0].text
    : outputs.map(x=>`### ${x.fileName}\n${x.text}`).join('\n');
  $('save').disabled = $('copy').disabled = !outputs.length;
  $('saveHint').textContent = errs.length ? 'Пропущено: ' + errs.join('; ')
    : outputs.length > 1 && o.fmt!=='clash' ? `Будет сохранено файлов: ${outputs.length}` : '';
  const f = $('files');
  f.textContent = sources.length ? sources.map(s=>s.name).join(', ') : 'Файлы не выбраны';
}

/* ---------- UI wiring ---------- */
const toast = AppToast;

function updateVisibility(){
  const f = radio('fmt');
  $('p-awg2').classList.toggle('hidden', f==='wiresock');
  $('p-ws').classList.toggle('hidden', f!=='wiresock');
  $('nolanRow').classList.toggle('hidden', f==='clash' || f==='xray');
  $('p-awg3').classList.toggle('hidden', f==='xray');
  $('i2-5').classList.toggle('hidden', f==='xray');
  $('awg3box').classList.toggle('hidden', !$('awg3').checked);
}

function init(){
  $('ports').innerHTML = PORTS.map(p=>`<label><input type="radio" name="port" value="${p}"${p===51820?' checked':''}><span>${p}</span></label>`).join('');
  document.querySelectorAll('#view-proton input,#view-proton select,#view-proton textarea').forEach(el=>{
    if (el.id==='file' || el.id==='domain' || el.id==='pasteText' || el.id==='out') return;
    const h = ()=>{ if (el.name==='fmt' && el.value==='xray'){ $('jc').value=5; $('jmin').value=40; $('jmax').value=70; document.querySelector('input[name=junk][value="3"]').checked = true; } if (el.name==='fmt'||el.id==='awg3') updateVisibility(); if (el.id==='jc'||el.id==='jmin'||el.id==='jmax') document.querySelector('input[name=junk][value="3"]').checked = true; regenerate(); };
    el.addEventListener('input', h); el.addEventListener('change', h);
  });

  $('pick').onclick = ()=>$('file').click();
  $('file').onchange = async e=>{
    const fl = Array.from(e.target.files);
    sources = [];
    for (const f of fl) { try { sources.push({name:f.name, text: await f.text()}); } catch(err) { alert('Не удалось прочитать файл: '+f.name); } }
    e.target.value = '';
    regenerate();
  };
  $('paste').onclick = ()=>$('dlgPaste').showModal();
  $('dlgPaste').addEventListener('close', ()=>{
    if ($('dlgPaste').returnValue==='ok' && $('pasteText').value.trim()){
      sources.push({name:`pasted_${sources.length+1}.conf`, text:$('pasteText').value});
      $('pasteText').value=''; regenerate();
    }
  });

  $('rndJunk').onclick = ()=>{
    const jc = rnd(1,100), jmin = rnd(1,200), jmax = rnd(jmin+1,201);
    $('jc').value=jc; $('jmin').value=jmin; $('jmax').value=jmax;
    document.querySelector('input[name=junk][value="3"]').checked = true; regenerate();
  };
  $('rndI').onclick = ()=>{ $('i1').value = pick(I1_PRESETS); [2,3,4,5].forEach(n=>$('i'+n).value=''); regenerate(); };
  $('rndWs').onclick = ()=>{ $('wsId').value = pick(DOMAINS); regenerate(); };
  $('rndAwg3').onclick = ()=>{
    const r = (a,b,c,d)=>`${rnd(a,b)}-${rnd(c,d)}`;
    $('cpa').value=r(5,49,50,110); $('mha').value=r(5,24,25,40); $('kt').value=r(5,10,11,25);
    $('rat').value=r(50,99,100,200); $('rkat').value=r(50,99,100,150); $('rt').value=r(3,9,10,15);
    regenerate();
  };
  $('genI').onclick = ()=>$('dlgDomain').showModal();
  $('dlgDomain').addEventListener('close', async ()=>{
    if ($('dlgDomain').returnValue!=='ok') return;
    const d = $('domain').value.trim();
    if (!d) return toast('Введите домен');
    const v = await window.generateI1FromDomain(d);
    if (!v) return toast('Не удалось сгенерировать I1');
    $('i1').value = v; [2,3,4,5].forEach(n=>$('i'+n).value=''); regenerate();
  });

  $('copy').onclick = async ()=>{
    const text = $('out').value;
    try { await navigator.clipboard.writeText(text); }
    catch { $('out').readOnly=false; $('out').select(); document.execCommand('copy'); $('out').readOnly=true; }
    toast('Скопировано');
  };
  $('save').onclick = saveFiles;
  updateVisibility(); regenerate();
}

async function saveFiles(){ if (outputs.length) AppSave(outputs, 'Конфиг AmneziaWG'); }

init();
