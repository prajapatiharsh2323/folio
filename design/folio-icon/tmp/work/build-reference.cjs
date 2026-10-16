// Build a fair RGBA reference from the original logo.png for verify --reference:
// un-blend the baked white background to transparency, scale to the 1024 square
// canvas with the same transform as the generated trace, apply the 224 rounded mask.
const fs = require("node:fs");
const zlib = require("node:zlib");

function decodePng(file) {
  const buf = fs.readFileSync(file);
  let o = 8, idat = [], w, h;
  while (o < buf.length) {
    const len = buf.readUInt32BE(o), type = buf.toString("ascii", o + 4, o + 8);
    if (type === "IHDR") { w = buf.readUInt32BE(o + 8); h = buf.readUInt32BE(o + 12); }
    if (type === "IDAT") idat.push(buf.subarray(o + 8, o + 8 + len));
    o += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const bpp = 4, stride = w * bpp, out = Buffer.alloc(w * h * 4);
  let so = 0, prev = null;
  for (let y = 0; y < h; y++) {
    const f = raw[so++]; const row = Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? row[x - bpp] : 0, b = prev ? prev[x] : 0,
        c = x >= bpp && prev ? prev[x - bpp] : 0;
      let v = raw[so++];
      if (f === 1) v = (v + a) & 255; else if (f === 2) v = (v + b) & 255;
      else if (f === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255; }
      row[x] = v;
    }
    row.copy(out, y * stride); prev = row;
  }
  return { w, h, data: out };
}

function crc32() {
  const t = [];
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return (b) => { let c = 0xffffffff; for (const x of b) c = t[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
}
function encodePng(w, h, rgba) {
  const crc = crc32();
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const tb = Buffer.from(type); const cr = Buffer.alloc(4); cr.writeUInt32BE(crc(Buffer.concat([tb, data])));
    return Buffer.concat([len, tb, data, cr]);
  };
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 6;
  const s = Buffer.alloc(h * (1 + w * 4)); let p = 0;
  for (let y = 0; y < h; y++) { s[p++] = 0; rgba.copy(s, p, y * w * 4, (y + 1) * w * 4); p += w * 4; }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ih), chunk("IDAT", zlib.deflateSync(s, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

const src = decodePng("D:/folio/design/folio-icon/folio-icon.origin.png");
const CHARCOAL = [35, 36, 47], VIOLET = [113, 92, 252];
const W = 1024, H = 1024;
const SCALE = W / src.h, OFFSET = (W - src.w * SCALE) / 2;
const R = 224;

const out = Buffer.alloc(W * H * 4); // (0,0,0,0) by default
for (let sy = 0; sy < src.h; sy++) {
  for (let sx = 0; sx < src.w; sx++) {
    const si = (sy * src.w + sx) * 4;
    const R0 = src.data[si], G0 = src.data[si + 1], B0 = src.data[si + 2];
    if (Math.min(R0, G0, B0) >= 240) continue; // baked white background -> transparent
    const dx = sx * SCALE + OFFSET, dy = sy * SCALE;
    const minX = Math.floor(dx), maxX = Math.ceil(dx), minY = Math.floor(dy), maxY = Math.ceil(dy);
    for (let x = minX; x <= maxX && x < W; x++) {
      for (let y = minY; y <= maxY && y < H; y++) {
        // rounded-square mask
        const cx = Math.max(R - x, x - (W - R), 0), cy = Math.max(R - y, y - (H - R), 0);
        if (Math.hypot(cx, cy) > R) {
          if (x > R && x < W - R && y > R && y < H - R) continue;
          continue;
        }
        // base-color recovery: c = base*a + 255*(1-a)  ->  a = (255-c)/(255-base)
        const isV = B0 > 140 && B0 > R0 + 20 && G0 < R0;
        const base = isV ? VIOLET : CHARCOAL;
        let a = ((255 - R0) / (255 - base[0]) + (255 - G0) / (255 - base[1]) + (255 - B0) / (255 - base[2])) / 3;
        a = Math.max(0, Math.min(1, a));
        const oi = (y * W + x) * 4;
        out[oi] = base[0]; out[oi + 1] = base[1]; out[oi + 2] = base[2];
        out[oi + 3] = Math.max(out[oi + 3], Math.round(a * 255));
      }
    }
  }
}
fs.writeFileSync("D:/folio/design/folio-icon/tmp/work/origin-square.png", encodePng(W, H, out));
console.log("un-matted reference written (transparent, rounded, square-aligned)");
