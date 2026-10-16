# Retrieval eval

Measures an embedding model the way the server ranks: which chunk answers each question, dense alone and fused with the server's sparse encoder. Run it before changing the model, its prompts or the sparse weight.

```bash
bun eval/chunks.ts <synthetic project> eval/.cache/chunks.json   # cut the corpus once
bun eval/eval.ts qwen3-embedding:0.6b [--sparse-weight 0.3]       # hit@1 / hit@5 / n per set
bun eval/speed.ts qwen3-embedding:0.6b                            # chunks per second, GPU and CPU
```

`eval.ts` holds each model family's prompts, the same as `application.yaml`. Vectors are cached per model under `eval/.cache/`, which git ignores; the misses of the last run are written there too.

## Corpus and questions

- **Corpus:** the synthetic Banco Contoso project (`sa-perf-project`): 50 invented institutional ADRs in Spanish and its PO Spec, 598 chunks. `chunks.ts` cuts them as `export-index.js` cuts a project, with ADRs by H2 over 6,000 characters. The longest chunk is 26,169 characters and the median 1,349.
- **Questions:** `questions.json`, 72 in all, each naming its target (an ADR, or a PO Spec use case) and the phrase the answering chunk contains.

| Set | n | What it tests |
|---|---|---|
| `base` | 50 | ordinary questions, 30 in Spanish and 20 in English over the Spanish text; the ADR's references, history and notes never count |
| `deep` | 12 | an answer more than 8,000 characters into a long chunk: whether the model reads the whole chunk |
| `ident` | 10 | an exact identifier (`ADR-027`, `SQLCipher`, `UC-08`): the sparse side's job |

## Results, 2026-10-14

These are hit@5 with the server's fusion at sparse weight 0.3, on Ollama 0.40.4 with an AMD Radeon RX 9060 XT (ROCm 7). Speed is the same 96 chunks, all layers on the GPU and then all on the CPU.

| Model | base | deep | ident | total | GPU | CPU | Context | License |
|---|---|---|---|---|---|---|---|---|
| `qwen3-embedding:0.6b` | 49 | 12 | 9 | **70** | 18.9/s | 1.1/s | 32K | Apache 2.0 (upstream) |
| `embeddinggemma` (v1) | 49 | 10 | 10 | 69 | 20.4/s | 8.8/s | 2K | Gemma Terms of Use |
| `snowflake-arctic-embed2` | 48 | 10 | 10 | 68 | 9.6/s | – | 8K | Apache 2.0 (upstream) |
| `bge-m3` | 49 | 9 | 10 | 68 | 9.9/s | – | 8K | MIT |
| `qwen3-embedding:4b` | 50 | 11 | 9 | 70 | 4.0/s | – | 40K | Apache 2.0 (upstream) |

The models that did not run:

- **`embeddinggemma-2`**: every tag needs Ollama's MLX runtime, which the Linux package does not ship.
- **all-minilm, bge-large, mxbai-embed-large, nomic-embed-text v1.5 and v2, paraphrase-multilingual, granite-embedding, snowflake-arctic-embed v1**: windows of 512 to 2K tokens, against chunks up to about 7K tokens.

What the numbers say:

- **The models tie on ordinary questions.** A gap of 1–3 of 72 is noise on one corpus. The choice rests on context, license and speed: Qwen3 4B ties 0.6B at 70 but is 4× slower with 2,560-dimension vectors.
- **Long chunks need a long window.** On `deep`, the 2K and 8K models miss answers the 32K and 40K models find. EmbeddingGemma v1 cuts off 43 of the 598 chunks (7%).
- **The sparse weight matters more than the model.** At equal weight (1.0) every model lost 2–5 first hits on `base`. At 0.3, `base` is back to 48–50 and `ident` stays at 9–10. Qwen3 0.6B totals 70 at 0.3, 68 at 0.4 and 67 at 0.5.
- **Smaller chunks did not help.** Re-cutting at 4,000 characters fixed the deep misses of the short-context models but cost every model on `base`.
- **Ollama's window must be sent.** Without `num_ctx`, Ollama loads Qwen3 at 4096 and cuts long chunks silently; the server sends 8192 on every request.
- **CPU speed differs by model.** Qwen3 on a CPU is 17× slower than on the GPU because it reads the whole window; a first full index is slow there, and releases re-embed only what changed.

## Limits

- One synthetic corpus, in Spanish, with questions written by the person who chose the labels.
- A released architecture measured inside the organization is the confirmation, since its text cannot leave the organization and so cannot be committed here.
