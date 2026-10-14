import * as fflate from "fflate";
import fs from "node:fs";
import path from "node:path";

export function createEpubZip(opts) {
  const zipObj = {};

  // mimetype must be stored uncompressed as the first entry
  zipObj["mimetype"] = [fflate.strToU8("application/epub+zip"), { level: 0 }];

  // META-INF/container.xml
  zipObj["META-INF/container.xml"] = fflate.strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>`);

  // OEBPS/style.css
  zipObj["OEBPS/style.css"] = fflate.strToU8(`
body {
  font-family: serif;
  line-height: 1.6;
  margin: 5%;
}
h1, h2, h3 {
  color: #2F6F5E;
}
p {
  margin-bottom: 1em;
  text-indent: 1em;
}
img {
  max-width: 100%;
  height: auto;
}
`);

  // Chapters
  const manifestItems = [
    `<item id="style" href="style.css" media-type="text/css"/>`,
    `<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>`
  ];
  const spineItems = [];
  const navList = [];

  opts.chapters.forEach((ch, idx) => {
    const filename = `chapter_${idx + 1}.xhtml`;
    manifestItems.push(`<item id="${ch.id}" href="${filename}" media-type="application/xhtml+xml"/>`);
    spineItems.push(`<itemref idref="${ch.id}"/>`);
    navList.push(`<li><a href="${filename}">${ch.title}</a></li>`);

    const xhtmlContent = `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${opts.language}" lang="${opts.language}">
<head>
  <title>${ch.title}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h2>${ch.title}</h2>
  ${ch.html}
</body>
</html>`;
    zipObj[`OEBPS/${filename}`] = fflate.strToU8(xhtmlContent);
  });

  // Images if any
  if (opts.images) {
    opts.images.forEach((img, idx) => {
      manifestItems.push(`<item id="img_${idx + 1}" href="images/${img.name}" media-type="image/jpeg"/>`);
      zipObj[`OEBPS/images/${img.name}`] = [img.data, { level: 0 }];
    });
  }

  // nav.xhtml
  zipObj["OEBPS/nav.xhtml"] = fflate.strToU8(`<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${opts.language}">
<head>
  <title>Table of Contents</title>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Table of Contents</h1>
    <ol>
      ${navList.join("\n      ")}
    </ol>
  </nav>
</body>
</html>`);

  // OEBPS/content.opf
  zipObj["OEBPS/content.opf"] = fflate.strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:folio-synthetic-fixture-${opts.title.toLowerCase().replace(/\s+/g, "-")}</dc:identifier>
    <dc:title>${opts.title}</dc:title>
    <dc:language>${opts.language}</dc:language>
    <dc:creator>${opts.creator}</dc:creator>
    <meta property="dcterms:modified">${new Date().toISOString().replace(/\.[0-9]+Z$/, "Z")}</meta>
  </metadata>
  <manifest>
    ${manifestItems.join("\n    ")}
  </manifest>
  <spine>
    ${spineItems.join("\n    ")}
  </spine>
</package>`);

  return fflate.zipSync(zipObj);
}

// Generate the 3 spike fixtures in packages/core/tests/fixtures and apps/web/public/fixtures
const fixturesDir = path.resolve(process.cwd(), "packages/core/tests/fixtures");
const publicFixturesDir = path.resolve(process.cwd(), "apps/web/public/fixtures");
fs.mkdirSync(fixturesDir, { recursive: true });
fs.mkdirSync(publicFixturesDir, { recursive: true });

// 1. English Novel
const englishEpub = createEpubZip({
  title: "The Solitary Reader",
  creator: "Arthur Pendelton",
  language: "en",
  chapters: [
    {
      id: "ch1",
      title: "Chapter I: The Discovery",
      html: `<p>The quiet afternoon sun slanted across the cedar bookshelves of the old library. Miles turned a yellowed page, listening to the soft rustle of aged cotton paper.</p><p>For years, readers had searched through the archived manuscripts for any trace of the lost cartographer's journal.</p>`
    },
    {
      id: "ch2",
      title: "Chapter II: The Journey North",
      html: `<p>Leaving the city before daybreak, the mountain path proved steeper than anticipated. The frost clung to the heather and pine needles like silver lace.</p><p>Every turn in the valley brought new vistas of undisturbed silence.</p>`
    }
  ]
});

// 2. Hindi Book (Devanagari conjuncts, nukta, matras)
const hindiEpub = createEpubZip({
  title: "निर्मल धारा (Nirmal Dhara)",
  creator: "प्रेमचंद (Premchand)",
  language: "hi",
  chapters: [
    {
      id: "ch1",
      title: "अध्याय १: नया प्रभात",
      html: `<p>सुबह की पहली किरण जब गंगा की लहरों पर पड़ी, तो चारों ओर एक अलौकिक शांति छा गई। पंडितजी ने अपनी पोथी खोली और शांत मन से अध्ययन करने लगे।</p><p>गाँव के लोग धीरे-धीरे नदी तट की ओर बढ़ रहे थे। पक्षियों का कलरव वातावरण को मधुर बना रहा था।</p>`
    },
    {
      id: "ch2",
      title: "अध्याय २: कर्म की राह",
      html: `<p>सत्य और निष्ठा का मार्ग कभी भी सरल नहीं होता। कठिनाइयों के बीच भी जो व्यक्ति अडिग रहता है, वही अपने लक्ष्य तक पहुँच पाता है।</p><p>विद्यार्थियों ने गुरुजी के वचनों को ध्यानपूर्वक सुना और मनन किया।</p>`
    }
  ]
});

// 3. Image-rich Book
const sampleImageBytes = new Uint8Array([
  0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
  0x00, 0x48, 0x00, 0x00, 0xff, 0xdb, 0x00, 0x43, 0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08,
  0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0a, 0x0c, 0x14, 0x0d, 0x0c, 0x0b, 0x0b, 0x0c, 0x19, 0x12,
  0x13, 0x0f, 0x14, 0x1d, 0x1a, 0x1f, 0x1e, 0x1d, 0x1a, 0x1c, 0x1c, 0x20, 0x24, 0x2e, 0x27, 0x20,
  0x22, 0x2c, 0x23, 0x1c, 0x1c, 0x28, 0x37, 0x29, 0x2c, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1f, 0x27,
  0x39, 0x3d, 0x38, 0x32, 0x3c, 0x2e, 0x33, 0x34, 0x32, 0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x01,
  0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xff, 0xc4, 0x00, 0x1f, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01,
  0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04,
  0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0xff, 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f,
  0x00, 0xbf, 0x00, 0xff, 0xd9
]);

const imageEpub = createEpubZip({
  title: "Flora of the Valley",
  creator: "Linnaeus Institute",
  language: "en",
  images: [{ name: "flower.jpg", data: sampleImageBytes }],
  chapters: [
    {
      id: "ch1",
      title: "Alpine Flora",
      html: `<p>The high meadows produce flora of remarkable resilience and pigmentation.</p><p><img src="images/flower.jpg" alt="Mountain Flower"/></p><p>Notice the compact cell structure adapting to high altitude UV exposure.</p>`
    }
  ]
});

// Save fixtures
fs.writeFileSync(path.join(fixturesDir, "english-novel.epub"), englishEpub);
fs.writeFileSync(path.join(fixturesDir, "hindi-book.epub"), hindiEpub);
fs.writeFileSync(path.join(fixturesDir, "image-book.epub"), imageEpub);

fs.writeFileSync(path.join(publicFixturesDir, "english-novel.epub"), englishEpub);
fs.writeFileSync(path.join(publicFixturesDir, "hindi-book.epub"), hindiEpub);
fs.writeFileSync(path.join(publicFixturesDir, "image-book.epub"), imageEpub);

console.log("Fixtures generated successfully for both packages/core/tests/fixtures and apps/web/public/fixtures.");
