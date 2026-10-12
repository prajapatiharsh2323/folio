import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

function createPng(width, height, rgbaBuffer) {
  const signature = Buffer.from([137, 80, 78, 72, 13, 10, 26, 10]);

  // IHDR chunk
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr.writeUInt8(8, 8); // bit depth
  ihdr.writeUInt8(6, 9); // color type (RGBA)
  ihdr.writeUInt8(0, 10); // compression method
  ihdr.writeUInt8(0, 11); // filter method
  ihdr.writeUInt8(0, 12); // interlace method
  const ihdrChunk = makeChunk("IHDR", ihdr);

  // Raw image data with filter byte (0) per scanline
  const scanlineLength = width * 4 + 1;
  const rawData = Buffer.alloc(scanlineLength * height);

  for (let y = 0; y < height; y++) {
    rawData[y * scanlineLength] = 0; // Filter None
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

// Table-based CRC32
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

// Generate Folio icon with forest green background (#2F6F5E) and cream book mark
function drawIcon(size, isMaskable) {
  const buf = Buffer.alloc(size * size * 4);
  const bgR = 0x2f, bgG = 0x6f, bgB = 0x5e;
  const fgR = 0xfa, fgG = 0xfa, fgB = 0xf7;

  const center = size / 2;
  const cornerRadius = isMaskable ? 0 : size * 0.22;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const idx = (y * size + x) * 4;

      let inShape = true;
      if (!isMaskable) {
        const dx = Math.max(0, Math.abs(x - center) - (center - cornerRadius));
        const dy = Math.max(0, Math.abs(y - center) - (center - cornerRadius));
        if (dx * dx + dy * dy > cornerRadius * cornerRadius) {
          inShape = false;
        }
      }

      if (!inShape) {
        buf[idx] = 0;
        buf[idx + 1] = 0;
        buf[idx + 2] = 0;
        buf[idx + 3] = 0;
        continue;
      }

      // Base background: forest green #2F6F5E
      buf[idx] = bgR;
      buf[idx + 1] = bgG;
      buf[idx + 2] = bgB;
      buf[idx + 3] = 255;

      // Central book icon region (within central safe area)
      const scale = size / 512;
      const nx = x / scale;
      const ny = y / scale;

      const inBookLeft = nx >= 150 && nx <= 245 && ny >= 140 && ny <= 370;
      const inBookRight = nx >= 267 && nx <= 362 && ny >= 140 && ny <= 370;

      if (inBookLeft || inBookRight) {
        buf[idx] = fgR;
        buf[idx + 1] = fgG;
        buf[idx + 2] = fgB;
        buf[idx + 3] = 255;

        const localY = (ny - 140) % 40;
        if (localY >= 18 && localY <= 24 && ((inBookLeft && nx <= 225) || (inBookRight && nx >= 285))) {
          buf[idx] = bgR;
          buf[idx + 1] = bgG;
          buf[idx + 2] = bgB;
          buf[idx + 3] = 255;
        }
      }
    }
  }

  return buf;
}

const outDir = path.resolve(process.cwd(), "apps/web/public/icons");
fs.mkdirSync(outDir, { recursive: true });

fs.writeFileSync(path.join(outDir, "icon-192.png"), createPng(192, 192, drawIcon(192, false)));
fs.writeFileSync(path.join(outDir, "icon-512.png"), createPng(512, 512, drawIcon(512, false)));
fs.writeFileSync(path.join(outDir, "maskable-512.png"), createPng(512, 512, drawIcon(512, true)));

console.log("Generated 192x192, 512x512, and maskable-512 PNG icons successfully.");
