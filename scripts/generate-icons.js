import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

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

function makeChunk(type, data) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typeBuffer = Buffer.from(type, "ascii");
  const crcData = Buffer.concat([typeBuffer, data]);
  const crc = crc32(crcData);
  const crcBuffer = Buffer.alloc(4);
  crcBuffer.writeUInt32BE(crc, 0);
  return Buffer.concat([length, typeBuffer, data, crcBuffer]);
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

function createPng(width, height, rgbaBuffer) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr.writeUInt8(8, 8); // bit depth
  ihdr.writeUInt8(6, 9); // RGBA
  ihdr.writeUInt8(0, 10);
  ihdr.writeUInt8(0, 11);
  ihdr.writeUInt8(0, 12);
  const ihdrChunk = makeChunk("IHDR", ihdr);

  const scanlineLength = width * 4 + 1;
  const rawData = Buffer.alloc(scanlineLength * height);

  for (let y = 0; y < height; y++) {
    rawData[y * scanlineLength] = 0;
    rgbaBuffer.copy(
      rawData,
      y * scanlineLength + 1,
      y * width * 4,
      (y + 1) * width * 4
    );
  }

  const compressed = zlib.deflateSync(rawData);
  const idatChunk = makeChunk("IDAT", compressed);
  const iendChunk = makeChunk("IEND", Buffer.alloc(0));

  return Buffer.concat([signature, ihdrChunk, idatChunk, iendChunk]);
}

function resizeImage(src, targetW, targetH, paddingFraction = 0) {
  const out = Buffer.alloc(targetW * targetH * 4);
  // Default background color from logo edges (nearly pure white / cream)
  for (let i = 0; i < out.length; i += 4) {
    out[i] = 254;
    out[i + 1] = 255;
    out[i + 2] = 254;
    out[i + 3] = 255;
  }

  const padX = targetW * paddingFraction;
  const padY = targetH * paddingFraction;
  const innerW = targetW - padX * 2;
  const innerH = targetH - padY * 2;

  const scale = Math.min(innerW / src.width, innerH / src.height);
  const renderW = Math.round(src.width * scale);
  const renderH = Math.round(src.height * scale);
  const offsetX = Math.round((targetW - renderW) / 2);
  const offsetY = Math.round((targetH - renderH) / 2);

  for (let y = 0; y < renderH; y++) {
    const sy = (y + 0.5) * (src.height / renderH) - 0.5;
    const y0 = Math.max(0, Math.min(src.height - 1, Math.floor(sy)));
    const y1 = Math.max(0, Math.min(src.height - 1, y0 + 1));
    const wy = Math.max(0, Math.min(1, sy - y0));

    for (let x = 0; x < renderW; x++) {
      const sx = (x + 0.5) * (src.width / renderW) - 0.5;
      const x0 = Math.max(0, Math.min(src.width - 1, Math.floor(sx)));
      const x1 = Math.max(0, Math.min(src.width - 1, x0 + 1));
      const wx = Math.max(0, Math.min(1, sx - x0));

      const dstIdx = ((offsetY + y) * targetW + (offsetX + x)) * 4;

      for (let c = 0; c < 4; c++) {
        const v00 = src.data[(y0 * src.width + x0) * 4 + c];
        const v10 = src.data[(y0 * src.width + x1) * 4 + c];
        const v01 = src.data[(y1 * src.width + x0) * 4 + c];
        const v11 = src.data[(y1 * src.width + x1) * 4 + c];

        const top = v00 * (1 - wx) + v10 * wx;
        const bot = v01 * (1 - wx) + v11 * wx;
        out[dstIdx + c] = Math.round(top * (1 - wy) + bot * wy);
      }
    }
  }
  return out;
}

const outDir = path.resolve(process.cwd(), "apps/web/public/icons");
const logoPath = path.join(outDir, "logo.png");

if (fs.existsSync(logoPath)) {
  const logo = decodePng(fs.readFileSync(logoPath));
  fs.writeFileSync(path.join(outDir, "icon-192.png"), createPng(192, 192, resizeImage(logo, 192, 192, 0.05)));
  fs.writeFileSync(path.join(outDir, "icon-512.png"), createPng(512, 512, resizeImage(logo, 512, 512, 0.05)));
  fs.writeFileSync(path.join(outDir, "maskable-512.png"), createPng(512, 512, resizeImage(logo, 512, 512, 0.15)));
  console.log("Generated icon-192.png, icon-512.png, and maskable-512.png from logo.png.");
} else {
  console.error("logo.png not found in", outDir);
}
