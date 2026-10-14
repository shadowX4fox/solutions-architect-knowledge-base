// Retrieval eval for the embedding model: which chunk answers each question, ranked the way the server ranks.
//
//   bun eval/chunks.ts <synthetic project> eval/.cache/chunks.json     cut the corpus once
//   bun eval/eval.ts <ollama model> [--chunks eval/.cache/chunks.json] [--sparse-weight 0.3]
//
// Each question names its target — an ADR (the chunk of it holding the answer phrase) or a PO Spec use case — and a
// set: base (ordinary questions, Spanish and English), deep (an answer deep inside a long chunk), ident (an exact
// identifier). Ranked dense only (what the model decides) and hybrid (dense + the server's sparse encoder, weighted
// reciprocal rank fusion, k = 60). Prints hit@1 / hit@5 / n per set. Vectors are cached per model under eval/.cache/.
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "fs";
import { join, dirname } from "path";

const args = process.argv.slice(2);
const opt = (k: string, d: string) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const model = args.find((a, i) => !a.startsWith("--") && !args[i - 1]?.startsWith("--"));
if (!model) { console.error("usage: bun eval/eval.ts <ollama model> [--chunks <file>] [--sparse-weight <w>]"); process.exit(2); }
const HERE = import.meta.dir; const CACHE = join(HERE, ".cache"); mkdirSync(CACHE, { recursive: true });
const chunksFile = opt("--chunks", join(CACHE, "chunks.json"));
const W = Number(opt("--sparse-weight", "0.3"));
const OLLAMA = process.env.OLLAMA_BASE_URL || "http://localhost:11434";

// the server's prompts per model family (application.yaml): Ollama adds none
const QWEN3 = "Instruct: Given a question about a software architecture, retrieve the passages that answer it\nQuery: ";
const PROMPTS: [RegExp, { ctx: number; q: string; d: (c: any) => string }][] = [
  [/^qwen3-embedding/, { ctx: 8192, q: QWEN3, d: (c) => c.text }],
  [/^embeddinggemma/, { ctx: 2048, q: "task: search result | query: ", d: (c) => `title: ${c.head || "none"} | text: ${c.text}` }],
  [/^snowflake-arctic-embed/, { ctx: 8192, q: "query: ", d: (c) => c.text }],
  [/.*/, { ctx: 8192, q: "", d: (c) => c.text }],
];
const cfg = PROMPTS.find(([re]) => re.test(model))![1];

const chunks: any[] = JSON.parse(readFileSync(chunksFile, "utf8"));
const qs: any[] = JSON.parse(readFileSync(join(HERE, "questions.json"), "utf8"));
async function embed(input: string[]): Promise<number[][]> {
  const r = await fetch(`${OLLAMA}/api/embed`, { method: "POST", body: JSON.stringify({ model, input, truncate: true, options: { num_ctx: cfg.ctx } }) });
  const j: any = await r.json(); if (!j.embeddings) throw new Error(JSON.stringify(j).slice(0, 300)); return j.embeddings;
}
const norm = (v: number[]) => { const n = Math.hypot(...v) || 1; return v.map((x) => x / n); };
const cacheFile = join(CACHE, `vec-${chunksFile.split("/").pop()}-${model.replace(/[:/]/g, "_")}.json`);
let D: number[][]; let secs = 0;
if (existsSync(cacheFile)) ({ D, secs } = JSON.parse(readFileSync(cacheFile, "utf8")));
else {
  const t = performance.now(); D = [];
  for (let i = 0; i < chunks.length; i += 8) D.push(...(await embed(chunks.slice(i, i + 8).map(cfg.d))).map(norm));
  secs = (performance.now() - t) / 1000; writeFileSync(cacheFile, JSON.stringify({ D, secs }));
}

// the server's sparse encoder (SparseEncoder.java): compounds and their parts, accents folded, stopwords out; idf as Qdrant's modifier
const STOP = new Set("a an and are as at be by for from how in is it of on or the to what when where which who why with does do el la los las un una y o de del en por para con que se es su al como cual cuando donde".split(" "));
const terms = (s: string) => { const out: string[] = []; for (const t of s.normalize("NFD").replace(/\p{M}+/gu, "").toLowerCase().match(/[\p{L}\p{N}]+(?:[-_./][\p{L}\p{N}]+)*/gu) || []) { if (/[-_./]/.test(t)) out.push(t); for (const p of t.match(/[\p{L}\p{N}]+/gu)!) if (!STOP.has(p) && (p.length > 1 || /\d/.test(p))) out.push(p); } return out; };
const tf = chunks.map((c) => { const m = new Map<string, number>(); terms(c.text).forEach((t) => m.set(t, (m.get(t) || 0) + 1)); return m; });
const df = new Map<string, number>(); tf.forEach((m) => m.forEach((_, t) => df.set(t, (df.get(t) || 0) + 1)));
const N = chunks.length; const idf = (t: string) => { const f = df.get(t) || 0; return Math.log(1 + (N - f + 0.5) / (f + 0.5)); };

// relevant: a use case's own PO Spec chunk, or the target ADR's chunk holding the answer phrase (an ordinary question
// never counts the ADR's references, history or notes)
const relevant = (q: any, c: any) => q.target.startsWith("UC-") ? c.file.startsWith("PRODUCT") && c.head.startsWith(q.target)
  : c.file.includes(`/${q.target}-`) && (q.set !== "base" || !/^(8|9|10)\. /.test(c.head)) && new RegExp(q.answer).test(c.text);

const Q = (await embed(qs.map((q) => cfg.q + q.question))).map(norm);
const stats: Record<string, { h1: number; h5: number; n: number }> = {}; const misses: string[] = [];
qs.forEach((q, qi) => {
  const dense = D.map((d, i) => [i, d.reduce((s, x, k) => s + x * Q[qi][k], 0)] as [number, number]).sort((a, b) => b[1] - a[1]);
  const qt = [...new Set(terms(q.question))];
  const sp = tf.map((m, i) => [i, qt.reduce((s, t) => s + (m.has(t) ? (1 + Math.log(m.get(t)!)) * idf(t) : 0), 0)] as [number, number]).filter((x) => x[1] > 0).sort((a, b) => b[1] - a[1]);
  const fused = new Map<number, number>();
  dense.slice(0, 50).forEach(([i], r) => fused.set(i, (fused.get(i) || 0) + 1 / (61 + r)));
  sp.slice(0, 50).forEach(([i], r) => fused.set(i, (fused.get(i) || 0) + W / (61 + r)));
  const hybrid = [...fused.entries()].sort((a, b) => b[1] - a[1]);
  for (const [name, list] of [["dense", dense], ["hybrid", hybrid]] as const) {
    const rank = list.slice(0, 10).findIndex(([i]) => relevant(q, chunks[i]));
    const s = (stats[`${q.set}/${name}`] ??= { h1: 0, h5: 0, n: 0 }); s.n++; if (rank === 0) s.h1++; if (rank >= 0 && rank < 5) s.h5++;
    if (name === "hybrid" && (rank < 0 || rank >= 5)) misses.push(`${q.set} ${q.target} [${q.lang}] ${q.question} → ${chunks[list[0][0]].id}`);
  }
});
const total = (name: string) => Object.entries(stats).filter(([k]) => k.endsWith(name)).reduce((a, [, v]) => a + v.h5, 0);
console.log(`${model}  ${D[0].length} dims · ${chunks.length} chunks in ${secs.toFixed(0)} s (${(chunks.length / secs).toFixed(1)}/s) · sparse weight ${W}`);
console.log("  " + Object.entries(stats).sort().map(([k, v]) => `${k} ${v.h1}/${v.h5}/${v.n}`).join("  ·  "));
console.log(`  hit@5 total: dense ${total("dense")}/${qs.length} · hybrid ${total("hybrid")}/${qs.length}`);
writeFileSync(join(CACHE, `misses-${model.replace(/[:/]/g, "_")}.txt`), misses.join("\n") + "\n");
