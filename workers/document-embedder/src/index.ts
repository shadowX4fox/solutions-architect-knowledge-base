/**
 * Minimal shape of the R2 event notification message body.
 * R2 publishes this to the queue when an object is created/updated.
 * Not included in @cloudflare/workers-types, so defined locally.
 */
interface R2EventNotification {
  object: {
    key: string; // e.g. "payments/overview.md"
  };
}

/**
 * Bindings declared in wrangler.toml and injected by the Workers runtime.
 * - R2_DOCS: the R2 bucket where architecture docs are stored
 * - AI: Cloudflare Workers AI gateway (used to run the embedding model)
 * - VECTORIZE: the Vectorize index that stores and queries embedding vectors
 */
export interface Env {
  R2_DOCS: R2Bucket;
  AI: Ai;
  VECTORIZE: VectorizeIndex;
}

/**
 * Splits a document into overlapping windows so that sentences that fall
 * near a chunk boundary appear in two consecutive chunks.
 * Without overlap, a query that matches text spanning a boundary would
 * retrieve an incomplete chunk and lose context.
 *
 * @param size    target chunk length in characters (default 1500)
 * @param overlap how many characters the next chunk re-reads from the end
 *                of the previous one (default 200)
 */
function chunkText(text: string, size = 1500, overlap = 200): string[] {
  const chunks: string[] = [];
  let start = 0;

  while (start < text.length) {
    chunks.push(text.slice(start, start + size));
    start += size - overlap;
  }

  return chunks;
}

/**
 * Core indexing pipeline for a single R2 object.
 *
 * Flow:
 *   R2 key → fetch text → chunk → embed → delete stale vectors → upsert
 *
 * Key decisions:
 * - Keys must follow the convention "<solution>/<filename>" so we can tag
 *   each vector with its solution namespace for filtered RAG queries.
 * - We embed BEFORE deleting stale vectors to minimise the window where
 *   the document is absent from the index (embed-then-replace strategy).
 * - Vector IDs are deterministic ("<solution>/<filename>#<chunkIndex>") so
 *   re-uploading the same file safely overwrites its previous vectors.
 * - MAX_CHUNKS caps the stale-delete sweep: we generate IDs #0–#99 and
 *   delete them all, covering any previous version regardless of how many
 *   chunks it had. Vectors that don't exist are silently ignored by Vectorize.
 */
async function processDocument(key: string, env: Env): Promise<void> {
  // Guard: only process keys that follow the "<solution>/<filename>" convention
  if (!key.includes("/")) return;

  const slashIndex = key.indexOf("/");
  const solution = key.slice(0, slashIndex);  // e.g. "payments"
  const filename = key.slice(slashIndex + 1); // e.g. "overview.md"

  const object = await env.R2_DOCS.get(key);
  if (!object) return; // object was deleted between the event and this invocation

  const text = await object.text();
  const chunks = chunkText(text);

  // env.AI.run() returns a union of sync and async response types; at runtime
  // the embedding call is always synchronous, so we cast to the sync shape.
  const response = await env.AI.run("@cf/baai/bge-base-en-v1.5", { text: chunks }) as { data: number[][] };
  const vectors = response.data; // parallel array: vectors[i] is the embedding for chunks[i]

  // Delete all possible stale vectors from any previous version of this file
  const MAX_CHUNKS = 100;
  const staleIds = Array.from(
    { length: MAX_CHUNKS },
    (_, i) => `${solution}/${filename}#${i}`
  );
  await env.VECTORIZE.deleteByIds(staleIds);

  // Build and upsert the new vectors, storing the raw chunk text in metadata
  // so the mcp-server can return it as context without a second R2 fetch.
  const upserts = chunks.map((chunk, i) => ({
    id: `${solution}/${filename}#${i}`,
    values: vectors[i],
    metadata: { solution, source: filename, chunkIndex: i, text: chunk },
  }));

  await env.VECTORIZE.upsert(upserts);
}

/**
 * Queue consumer entry point.
 *
 * Triggered by R2 object-create events forwarded to the "docs-upload-events"
 * queue (wired in the R2 bucket's event notification settings).
 * The Workers platform retries failed messages automatically (max_retries: 3).
 */
export default {
  async queue(batch: MessageBatch<R2EventNotification>, env: Env): Promise<void> {
    for (const message of batch.messages) {
      await processDocument(message.body.object.key, env);
    }
  },
} satisfies ExportedHandler<Env, R2EventNotification>;
