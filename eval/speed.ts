// Embedding speed: the first 96 chunks, on the GPU Ollama picked and with every layer on the CPU (num_gpu 0).
//   bun eval/speed.ts <ollama model>… [--chunks eval/.cache/chunks.json]
import { readFileSync } from "fs";
import { join } from "path";
const args = process.argv.slice(2); const i = args.indexOf("--chunks");
const file = i >= 0 ? args[i + 1] : join(import.meta.dir, ".cache", "chunks.json");
const models = args.filter((a, k) => !a.startsWith("--") && args[k - 1] !== "--chunks");
const OLLAMA = process.env.OLLAMA_BASE_URL || "http://localhost:11434";
const chunks: any[] = JSON.parse(readFileSync(file, "utf8")).slice(0, 96);
const call = async (model: string, input: string[], options: any) => {
  const r: any = await (await fetch(`${OLLAMA}/api/embed`, { method: "POST", body: JSON.stringify({ model, input, options, truncate: true }) })).json();
  if (!r.embeddings) throw new Error(JSON.stringify(r));
};
for (const model of models) for (const gpu of [true, false]) {
  const options: any = { num_ctx: 8192 }; if (!gpu) options.num_gpu = 0;
  await call(model, ["warm up"], options);
  const t = performance.now();
  for (let k = 0; k < chunks.length; k += 8) await call(model, chunks.slice(k, k + 8).map((c) => c.text), options);
  console.log(`${model.padEnd(32)} ${gpu ? "GPU" : "CPU"}  ${(chunks.length / ((performance.now() - t) / 1000)).toFixed(1)} chunks/s`);
}
