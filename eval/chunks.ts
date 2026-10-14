// Cuts a synthetic project into chunks the way export-index.js does: an ADR whole under 6,000 characters, else by H2;
// the PO Spec by H2 / H3. Context line first, comments out.   bun eval/chunks.ts <project> <out.json>
import { readFileSync, readdirSync, writeFileSync } from "fs";
import { join } from "path";
const ROOT = process.argv[2]; const out: any[] = [];
const clean = (s: string) => s.replace(/<!--[\s\S]*?-->/g, "").replace(/\r\n?/g, "\n").trim();
function cut(file: string, text: string, level: RegExp, ctx: string) {
  const lines = text.split("\n"); let head = "(intro)"; let buf: string[] = [];
  const flush = () => { const t = clean(buf.join("\n")); if (t.length > 40) out.push({ id: `${file}#${head}`, file, head, text: `${ctx} › ${head}\n\n${t}` }); buf = []; };
  for (const l of lines) { const m = l.match(level); if (m) { flush(); head = clean(m[1]); } buf.push(l); }
  flush();
}
for (const f of readdirSync(join(ROOT, "adr")).filter((x) => /^ADR-\d+.*\.md$/.test(x)).sort()) {
  const t = readFileSync(join(ROOT, "adr", f), "utf8"); const title = clean((t.match(/^# (.+)$/m) || [, f])[1]);
  if (t.length < 6000) out.push({ id: `adr/${f}#`, file: `adr/${f}`, head: "", text: `Banco Contoso › ${title}\n\n${clean(t)}` });
  else cut(`adr/${f}`, t, /^## (.+)$/, `Banco Contoso › ${title}`);
}
cut("PRODUCT_OWNER_SPEC.md", readFileSync(join(ROOT, "PRODUCT_OWNER_SPEC.md"), "utf8"), /^#{2,3} (.+)$/, "Banco Contoso › Especificación del Product Owner");
writeFileSync(process.argv[3] || "chunks.json", JSON.stringify(out));
const L = out.map((c) => c.text.length).sort((a, b) => a - b);
console.log(out.length, "chunks; chars p50", L[L.length >> 1], "p95", L[Math.floor(L.length * 0.95)], "max", L[L.length - 1]);
