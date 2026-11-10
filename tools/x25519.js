/* Чистая реализация X25519 (RFC 7748) на BigInt — не зависит от WebCrypto */
const X25519 = (() => {
  const P = (1n << 255n) - 19n;
  const A24 = 121665n;
  const mod = a => { const r = a % P; return r >= 0n ? r : r + P; };
  const pow = (b, e) => { let r = 1n; b = mod(b); while (e > 0n) { if (e & 1n) r = mod(r * b); b = mod(b * b); e >>= 1n; } return r; };
  const inv = a => pow(a, P - 2n);
  const decodeLE = bytes => { let n = 0n; for (let i = bytes.length - 1; i >= 0; i--) n = (n << 8n) | BigInt(bytes[i]); return n; };
  const encodeLE = n => { const o = new Uint8Array(32); for (let i = 0; i < 32; i++) { o[i] = Number(n & 255n); n >>= 8n; } return o; };
  function scalarMult(k, u) {
    const kk = Uint8Array.from(k); kk[0] &= 248; kk[31] &= 127; kk[31] |= 64;
    const uu = Uint8Array.from(u); uu[31] &= 127;
    const x1 = decodeLE(uu), kn = decodeLE(kk);
    let x2 = 1n, z2 = 0n, x3 = x1, z3 = 1n, swap = 0n;
    for (let t = 254n; t >= 0n; t--) {
      const kt = (kn >> t) & 1n;
      swap ^= kt;
      if (swap) { [x2, x3] = [x3, x2]; [z2, z3] = [z3, z2]; }
      swap = kt;
      const A = mod(x2 + z2), AA = mod(A * A), B = mod(x2 - z2), BB = mod(B * B), E = mod(AA - BB);
      const C = mod(x3 + z3), D = mod(x3 - z3), DA = mod(D * A), CB = mod(C * B);
      x3 = mod((DA + CB) ** 2n); z3 = mod(x1 * mod((DA - CB) ** 2n));
      x2 = mod(AA * BB); z2 = mod(E * (AA + A24 * E));
    }
    if (swap) { [x2, x3] = [x3, x2]; [z2, z3] = [z3, z2]; }
    return encodeLE(mod(x2 * inv(z2)));
  }
  const BASE = (() => { const b = new Uint8Array(32); b[0] = 9; return b; })();
  const b64 = u8 => btoa(String.fromCharCode(...u8));
  function generateKeyPair() {
    const priv = new Uint8Array(32); crypto.getRandomValues(priv);
    priv[0] &= 248; priv[31] &= 127; priv[31] |= 64;
    return { priv: b64(priv), pub: b64(scalarMult(priv, BASE)) };
  }
  return { scalarMult, generateKeyPair, BASE };
})();
