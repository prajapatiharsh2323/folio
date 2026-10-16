const fs = require("node:fs");
for (const n of ["book", "bookmark"]) {
  const svg = fs.readFileSync(`D:/folio/design/folio-icon/tmp/work/${n}-path.svg`, "utf8");
  const m = / d="([^"]*)"/.exec(svg);
  const d = m ? m[1] : "";
  const subs = d.split(/(?=M )/).filter(Boolean).length;
  console.log(n, "path chars", d.length, "subpaths", subs);
}
