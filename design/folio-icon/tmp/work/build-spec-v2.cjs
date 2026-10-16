// Build folio-icon spec v2: trace-based conversion of the project-local original logo.png
// (charcoal open book + violet bookmark on transparent canvas). Scratch = allowed tmp/work.
const fs = require("node:fs");

const WORK = "D:/folio/design/folio-icon/tmp/work";
const OUT = "D:/folio/design/folio-icon";
const d = (n) => / d="([^"]*)"/.exec(fs.readFileSync(`${WORK}/${n}-path.svg`, "utf8"))[1];

// 678x716 trace -> 1024 square canvas: center horizontally, scale uniformly.
const SCALE = 1024 / 716;
const OFFSET = (1024 - 678 * SCALE) / 2;
const layer = (name, fillColor, traceName, order) => ({
  name,
  svg:
    `<g transform="translate(${OFFSET.toFixed(2)} 0) scale(${SCALE.toFixed(5)})">` +
    `<path d="${d(traceName)}" fill="${fillColor}" fill-rule="evenodd"/>` +
    `</g>`,
  composer: { format: "svg", reason: "clean curve-fitted component traces stay editable" },
});

const spec = {
  name: "folio-icon",
  target_platforms: ["Web (PWA)"],
  source_reference: "apps/web/public/icons/logo.png (project-local original Folio logo: charcoal two-page open book with violet bookmark, transparent canvas 678x716)",
  brief:
    "App icon converted from the ORIGINAL Folio logo (user clarified: the logo is the original charcoal+bookmark logo.png, not the green-book mark). Fully transparent canvas per user request — no plate, no background. Original colors preserved: charcoal #23242F book, violet #715CFC flat bookmark.",
  semantic_review: {
    status: "visually-close",
    identity_risk: "low",
    basis:
      "owned project-local source raster (logo.png); per-component color segmentation + potrace curve fitting; silhouette, gap rhythm, page curves and bookmark geometry read as the same mark at glance; awaiting human acceptance",
    route: "semantic-svg",
    notes:
      "Traced from the owned raster per component (component-level vectorization was allowed since layer semantics separate cleanly by color). Flat colors sampled from pixels. No plate/background layers; no material helpers.",
  },
  source_analysis: {
    fidelity_target:
      "original Folio logo: charcoal two-page open book with center transparent gap, violet flat bookmark overlapping the right page; transparent background",
    identity_risk: "low",
    route: "semantic-svg",
    must_preserve: [
      "two-page silhouette with center gap, rounded outer corners, wing-like page curves",
      "violet flat bookmark overlapping the right page top-right, notch pointing down",
      "charcoal #23242F and violet #715CFC color roles",
      "fully transparent canvas",
    ],
    must_not_add: [
      "green plate or any filled background",
      "material effects, glow, shadow, bevel, text, frames",
      "page rules/spine lines (the original logo has none)",
    ],
    components: [
      {
        id: "book",
        layer: "open book",
        format: "svg",
        source_provenance: "project-local",
        alignment_basis: "color-segmented from logo.png, traced, uniformly scaled to 1024 and centered",
        spec_constraints: ["compound path, two disconnected page subpaths as in source", "flat charcoal fill"],
      },
      {
        id: "bookmark",
        layer: "bookmark",
        format: "svg",
        source_provenance: "project-local",
        alignment_basis: "color-segmented from logo.png, traced, same canvas transform as book layer",
        spec_constraints: ["single subpath", "flat violet fill", "frontmost layer above book"],
      },
    ],
    acceptance_checks: [
      "overlay glance vs logo.png silhouette: same mark, same gap, same bookmark placement",
      "no svg_material_warnings",
      "reads on dark and light backdrops (transparent logo variant caveat recorded)"
    ],
  },
  layers: [layer("open book", "#23242F", "book"), layer("bookmark", "#715CFC", "bookmark")],
};

fs.writeFileSync(`${OUT}/tmp/work/spec.json`, JSON.stringify(spec, null, 2) + "\n");
// Swap visible evidence to the selected source: exactly one origin file at the root.
const originSvg = `${OUT}/folio-icon.origin.svg`;
if (fs.existsSync(originSvg)) fs.rmSync(originSvg);
fs.copyFileSync("D:/folio/apps/web/public/icons/logo.png", `${OUT}/folio-icon.origin.png`);
console.log("spec v2 written; origin evidence = folio-icon.origin.png");
