#!/usr/bin/env python3
"""Собирает www/index.html из частей в tools/ (без сети)."""
import json, pathlib
root = pathlib.Path(__file__).parent
rd = lambda n: (root/n).read_text(encoding='utf-8')

proton_js = (rd('parts_proton.js')
    .replace('__I1__', json.dumps(json.loads(rd('i1_list.json')), ensure_ascii=False))
    .replace('__DOMAINS__', json.dumps(json.loads(rd('domains.json')), ensure_ascii=False))
    .replace('__NOLAN__', rd('nolan.txt').strip()))
warp_js = rd('parts_warp.js').replace('__WARPDATA__', json.dumps(json.loads(rd('warp_data.json')), ensure_ascii=False, separators=(',', ':')))

t = rd('template.html')
for k, v in {
    '__CSS__': rd('parts_css.css'),
    '__WARP_HTML__': rd('parts_warp.html'),
    '__PROTON_HTML__': rd('parts_proton.html'),
    '__QUIC__': rd('quic_core.js'),
    '__X25519__': rd('x25519.js'),
    '__WARP_JS__': warp_js,
    '__PROTON_JS__': proton_js,
}.items():
    t = t.replace(k, v)
assert '__' not in ''.join(c for c in [x for x in ['__CSS__','__WARP_JS__','__PROTON_JS__','__QUIC__'] if x in t]), 'не все плейсхолдеры заменены'
out = root.parent/'www'/'index.html'
out.parent.mkdir(exist_ok=True)
out.write_text(t, encoding='utf-8')
print('OK', out, len(t), 'bytes')
