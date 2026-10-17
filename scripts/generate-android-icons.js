// Generates the Android launcher icon layers for the Folio mobile app from the
// canonical brand logo (folio-logo.png at the repo root, transparent bg,
// orange gradient mark).
//
// Outputs:
//   mobile/app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/
//     ic_launcher_foreground.png   - adaptive foreground: mark centered on the
//                                    108dp canvas at 60/108 height, transparent
//     ic_launcher_monochrome.png   - same silhouette (white RGB), for themed icons
//   design/folio-icon/android/play-store-512.png
//                                    - 512x512 Play listing icon: charcoal plate
//                                      #23242F + mark (same 60/108 proportion)
//
// Usage: node scripts/generate-android-icons.js   (run from the repo root)
//
// Zero dependencies (node stdlib only), same style as scripts/generate-icons.js.
// Resampling is premultiplied-alpha bilinear so edges stay clean (no dark halo
// from transparent-black source pixels).

import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

// --- minimal PNG codec (RGBA8, matches scripts/generate-icons.js) -----------

function decodePng(buf) {
  let offset = 8;
  const idatChunks = [];
  let width, height;
  while (offset < buf.length) {
    const len = buf.readUInt32BE(offset);
    const type = buf.toString("ascii", offset + 4, offset + 8);
    if (type === "IHDR") {
      width = buf.readUInt32BE(offset + 8);
      height = buf.readUInt32BE(offset + 12);
    } else if (type === "IDAT") {
      idatChunks.push(buf.subarray(offset + 8, offset + 8 + len));
    }
    offset += 12 + len;
  }
  const decompressed = zlib.inflateSync(Buffer.concat(idatChunks));
  const bpp = 4;
  const stride = width * bpp;
  const out = Buffer.alloc(width * height * 4);
  let srcOffset = 0;
  let prevRow = null;

  for (let y = 0; y < height; y++) {
    const filter = decompressed[srcOffset++];
    const row = Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const raw = decompressed[srcOffset++];
      const a = x >= bpp ? row[x - bpp] : 0;
      const b = prevRow ? prevRow[x] : 0;
      const c = x >= bpp && prevRow ? prevRow[x - bpp] : 0;
      let val = raw;
      if (filter === 1) val = (raw + a) & 0xff;
      else if (filter === 2) val = (raw + b) & 0xff;
      else if (filter === 3) val = (raw + Math.floor((a + b) / 2)) & 0xff;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        const pr = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
        val = (raw + pr) & 0xff;
      }
      row[x] = val;
    }
    row.copy(out, y * stride);
    prevRow = row;
  }
  return { width, height, data: out };
}

const crcTable = [];
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  crcTable[n] = c;
}

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function makeChunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typeBuffer = Buffer.from(type, "ascii");
  const crcBuffer = Buffer.alloc(4);
  crcBuffer.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
  return Buffer.concat([length, typeBuffer, data, crcBuffer]);
}

function createPng(width, height, rgbaBuffer) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr.writeUInt8(8, 8); // bit depth
  ihdr.writeUInt8(6, 9); // RGBA
  const scanlineLength = width * 4 + 1;
  const rawData = Buffer.alloc(scanlineLength * height);
  for (let y = 0; y < height; y++) {
    rawData[y * scanlineLength] = 0; // filter: none
    rgbaBuffer.copy(
      rawData,
      y * scanlineLength + 1,
      y * width * 4,
      (y + 1) * width * 4
    );
  }
  return Buffer.concat([
    signature,
    makeChunk("IHDR", ihdr),
    makeChunk("IDAT", zlib.deflateSync(rawData, { level: 9 })),
    makeChunk("IEND", Buffer.alloc(0)),
  ]);
}

// --- image ops ---------------------------------------------------------------

/** Tight crop to the alpha bounding box (alpha strictly greater than `threshold`). */
function trimToAlpha(src, threshold = 8) {
  const { width, height, data } = src;
  let minX = width, minY = height, maxX = -1, maxY = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (data[(y * width + x) * 4 + 3] > threshold) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  if (maxX < 0) throw new Error("folio-logo.png has no opaque pixels");
  const w = maxX - minX + 1;
  const h = maxY - minY + 1;
  const out = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    data.copy(
      out,
      y * w * 4,
      ((minY + y) * width + minX) * 4,
      ((minY + y) * width + minX + w) * 4
    );
  }
  return { width: w, height: h, data: out };
}

/**
 * Premultiplied-alpha bilinear resample of `src` into a `targetW x targetH`
 * canvas; the result is centered on the canvas. `markHeightFraction` of the
 * canvas height is the mark's rendered height (width follows the aspect).
 */
function renderOnCanvas(src, canvas, markHeightFraction) {
  const markH = Math.round(canvas * markHeightFraction);
  const markW = Math.round((markH * src.width) / src.height);
  const offsetX = Math.round((canvas - markW) / 2);
  const offsetY = Math.round((canvas - markH) / 2);

  const out = Buffer.alloc(canvas * canvas * 4); // transparent
  const sxRatio = src.width / markW;
  const syRatio = src.height / markH;

  for (let y = 0; y < markH; y++) {
    const sy = (y + 0.5) * syRatio - 0.5;
    const y0 = Math.max(0, Math.min(src.height - 1, Math.floor(sy)));
    const y1 = Math.max(0, Math.min(src.height - 1, y0 + 1));
    const wy = Math.max(0, Math.min(1, sy - y0));

    for (let x = 0; x < markW; x++) {
      const sx = (x + 0.5) * sxRatio - 0.5;
      const x0 = Math.max(0, Math.min(src.width - 1, Math.floor(sx)));
      const x1 = Math.max(0, Math.min(src.width - 1, x0 + 1));
      const wx = Math.max(0, Math.min(1, sx - x0));

      const i00 = (y0 * src.width + x0) * 4;
      const i10 = (y0 * src.width + x1) * 4;
      const i01 = (y1 * src.width + x0) * 4;
      const i11 = (y1 * src.width + x1) * 4;

      // Premultiplied bilinear across RGBA.
      const pm = (i) => [src.data[i] * src.data[i + 3], src.data[i + 1] * src.data[i + 3], src.data[i + 2] * src.data[i + 3], src.data[i + 3]];
      const a00 = pm(i00), a10 = pm(i10), a01 = pm(i01), a11 = pm(i11);
      const px = new Array(4);
      for (let c = 0; c < 4; c++) {
        const top = a00[c] * (1 - wx) + a10[c] * wx;
        const bot = a01[c] * (1 - wx) + a11[c] * wx;
        px[c] = top * (1 - wy) + bot * wy;
      }
      const alpha = px[3];
      const dst = ((offsetY + y) * canvas + (offsetX + x)) * 4;
      if (alpha <= 0) continue;
      // Un-premultiply (channels were multiplied by alpha in 0..255).
      out[dst] = Math.min(255, Math.round(px[0] / alpha));
      out[dst + 1] = Math.min(255, Math.round(px[1] / alpha));
      out[dst + 2] = Math.min(255, Math.round(px[2] / alpha));
      out[dst + 3] = Math.min(255, Math.round(alpha));
    }
  }
  return { canvas, data: out };
}

/** Recolor every pixel to `rgb` keeping alpha (monochrome/themed-icon layer). */
function toMonochrome(rendered) {
  const { canvas, data } = rendered;
  const out = Buffer.from(data);
  for (let i = 0; i < out.length; i += 4) {
    if (out[i + 3] === 0) continue;
    out[i] = 255;
    out[i + 1] = 255;
    out[i + 2] = 255;
  }
  return { canvas, data: out };
}

/** Composite the rendered (transparent) mark over an opaque plate color. */
function overPlate(rendered, hex) {
  const { canvas, data } = rendered;
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  const out = Buffer.alloc(canvas * canvas * 4);
  for (let i = 0; i < out.length; i += 4) {
    const a = data[i + 3] / 255;
    out[i] = Math.round(data[i] * a + r * (1 - a));
    out[i + 1] = Math.round(data[i + 1] * a + g * (1 - a));
    out[i + 2] = Math.round(data[i + 2] * a + b * (1 - a));
    out[i + 3] = 255;
  }
  return { canvas, data: out };
}

// --- main --------------------------------------------------------------------

const root = process.cwd();
const logoPath = path.join(root, "folio-logo.png");
if (!fs.existsSync(logoPath)) {
  console.error("folio-logo.png not found at the repo root; run from the repo root.");
  process.exit(1);
}

// Adaptive icon contract: 108dp layer canvas; content at 60dp height (inside the
// 66dp circular safe zone) with the mark centered.
const MARK_HEIGHT_FRACTION = 60 / 108;
const DENSITIES = [
  ["mdpi", 108],
  ["hdpi", 162],
  ["xhdpi", 216],
  ["xxhdpi", 324],
  ["xxxhdpi", 432],
];

const source = decodePng(fs.readFileSync(logoPath));
const mark = trimToAlpha(source);
console.log(`mark: ${mark.width}x${mark.height} (from ${source.width}x${source.height})`);

const resDir = path.join(root, "mobile", "app", "src", "main", "res");
for (const [density, px] of DENSITIES) {
  const rendered = renderOnCanvas(mark, px, MARK_HEIGHT_FRACTION);
  const dir = path.join(resDir, `mipmap-${density}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "ic_launcher_foreground.png"), createPng(px, px, rendered.data));
  const mono = toMonochrome(rendered);
  fs.writeFileSync(path.join(dir, "ic_launcher_monochrome.png"), createPng(px, px, mono.data));
  console.log(`mipmap-${density}: foreground + monochrome @ ${px}px`);
}

// Play listing icon (M6 will upload it): same proportion on the charcoal plate.
const play = overPlate(renderOnCanvas(mark, 512, MARK_HEIGHT_FRACTION), "#23242F");
const playDir = path.join(root, "design", "folio-icon", "android");
fs.mkdirSync(playDir, { recursive: true });
fs.writeFileSync(path.join(playDir, "play-store-512.png"), createPng(512, 512, play.data));
console.log("play-store-512.png written to design/folio-icon/android/");
