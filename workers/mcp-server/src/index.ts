import { McpAgent } from "agents/mcp";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import Anthropic from "@anthropic-ai/sdk";
import { z } from "zod";

/**
 * Bindings declared in wrangler.toml and injected by the Workers runtime.
 * - R2_DOCS: the R2 bucket where architecture docs are stored (read-only here)
 * - AI: Cloudflare Workers AI gateway (used to embed the user's question)
 * - VECTORIZE: the Vectorize index populated by the document-embedder worker
 * - ANTHROPIC_API_KEY: secret stored via `wrangler secret put`, never in wrangler.toml
 * - MCP_OBJECT: Durable Object namespace that backs the DocsAgent sessions
 */
export interface Env {
  R2_DOCS: R2Bucket;
  AI: Ai;
  VECTORIZE: VectorizeIndex;
  ANTHROPIC_API_KEY: string;
  MCP_OBJECT: DurableObjectNamespace;
}

/**
 * DocsAgent — a stateful MCP server exposed as a Cloudflare Durable Object.
 *
 * Why a Durable Object?
 * MCP requires a persistent, bidirectional session between the client (Claude
 * Code) and the server so that tool registrations and conversation state survive
 * across multiple tool calls. Cloudflare Workers are stateless by default, so
 * we use McpAgent (which extends DurableObject) to provide that statefulness.
 * Each connected client gets its own isolated DO instance.
 *
 * Why init() instead of a constructor?
 * McpAgent's lifecycle calls init() once after the DO is hydrated. We register
 * tools here so they're set up exactly once per session. The Anthropic client
 * is also instantiated here so the API key binding is available (it isn't
 * accessible in a constructor before the runtime injects env).
 *
 * Exposed via: DocsAgent.mount("/mcp") → handles HTTP upgrade → SSE transport
 */
export class DocsAgent extends McpAgent {
  server = new McpServer({ name: "docs-search", version: "1.0.0" });
  // Declared with ! because TypeScript can't see that init() always runs before
  // any tool callback. Using a definite assignment assertion avoids a null check
  // inside every tool handler.
  private anthropic!: Anthropic;

  async init() {
    // Instantiated once here and reused across all tool calls in this session,
    // avoiding the overhead of creating a new client per request.
    this.anthropic = new Anthropic({ apiKey: (this.env as Env).ANTHROPIC_API_KEY });

    // -------------------------------------------------------------------------
    // Tool: list_solutions
    // Lists all solution doc collections in R2 with their doc counts.
    //
    // R2's delimiter listing works like a virtual directory: passing delimiter "/"
    // returns common prefixes (e.g. "payments/", "identity/") instead of every
    // individual object key. We then do a second list per prefix to count .md
    // files, giving Claude a map of what knowledge is available before searching.
    // -------------------------------------------------------------------------
    this.server.tool(
      "list_solutions",
      "List all available solution doc collections",
      {},
      async () => {
        const env = this.env as Env;
        const listing = await env.R2_DOCS.list({ delimiter: "/" });
        const results: { solution: string; count: number }[] = [];

        for (const prefix of listing.delimitedPrefixes ?? []) {
          const solution = prefix.slice(0, -1); // strip trailing "/"
          const files = await env.R2_DOCS.list({ prefix });
          const count = files.objects.filter(obj => obj.key.endsWith(".md")).length;
          results.push({ solution, count });
        }

        const lines = results.map(r => `• ${r.solution} (${r.count} docs)`);
        const text = `Found ${results.length} solutions:\n${lines.join("\n")}`;

        // MCP tool responses must return { content: [{ type, text }] }
        return { content: [{ type: "text" as const, text }] };
      }
    );

    // -------------------------------------------------------------------------
    // Tool: search_docs
    // RAG pipeline: embed question → vector search → grounded Claude answer.
    //
    // Why vector search instead of sending all docs to Claude?
    // The full doc corpus can be hundreds of files. Sending everything would
    // blow the context window and add cost. Instead we embed the question with
    // the SAME model used at index time (bge-base-en-v1.5) so the vector spaces
    // are compatible, retrieve the top-5 most semantically similar chunks, and
    // give only those to Claude. This keeps latency and token cost low while
    // still grounding the answer in the actual documentation.
    //
    // The optional `solution` parameter enables metadata filtering in Vectorize
    // so searches can be scoped to a single solution namespace.
    // -------------------------------------------------------------------------
    this.server.tool(
      "search_docs",
      "Search architecture docs and return a grounded answer with source citations",
      { question: z.string(), solution: z.string().optional() },
      async ({ question, solution }) => {
        const env = this.env as Env;

        // 1. Embed the question using the same model used to index the docs.
        //    Consistency is critical: mismatched models produce incompatible
        //    vector spaces and retrieval silently degrades.
        const embeddingResponse = await env.AI.run("@cf/baai/bge-base-en-v1.5", {
          text: [question],
        });
        // Cast needed: the AI binding types the response as a union of sync/async
        // shapes; at runtime this call is always synchronous.
        const questionVector = (embeddingResponse as { data: number[][] }).data[0];

        // 2. Retrieve the top-5 semantically closest chunks from Vectorize.
        //    returnMetadata: "all" brings back the stored chunk text so we don't
        //    need a second R2 fetch. The optional solution filter scopes results
        //    to a single namespace using Vectorize metadata filtering.
        const matches = await env.VECTORIZE.query(questionVector, {
          topK: 5,
          returnMetadata: "all",
          filter: solution ? { solution } : undefined,
        });

        // 3. Concatenate chunk texts into a context block for the LLM.
        const context = matches.matches
          .map(m => m.metadata?.text as string)
          .join("\n\n");

        // 4. Ask Claude to answer strictly from the retrieved context.
        //    The system prompt enforces grounding and citation so the answer
        //    is traceable back to specific source documents.
        const systemPrompt = `You are a helpful assistant answering questions about solution architecture docs.
Answer strictly from the context provided. If the answer is not in the context, say so.
Always cite the source document for each piece of information you use.`;

        const userMessage = `Context:\n${context}\n\nQuestion: ${question}`;

        const message = await this.anthropic.messages.create({
          model: "claude-sonnet-4-6",
          max_tokens: 1024,
          system: systemPrompt,
          messages: [{ role: "user", content: userMessage }],
        });

        const answer = message.content[0].type === "text" ? message.content[0].text : "";

        // 5. Deduplicate source filenames across all matched chunks and append
        //    them as a citation block so the caller can trace the answer back
        //    to specific documents.
        const sources = [...new Set(matches.matches.map(m => m.metadata?.source as string))];
        const citations = sources.map(s => `• ${s}`).join("\n");
        const text = `${answer}\n\nSources:\n${citations}`;

        return { content: [{ type: "text" as const, text }] };
      }
    );
  }
}

/**
 * Worker entry point.
 *
 * DocsAgent.mount("/mcp") returns a fetch handler that:
 * 1. Routes incoming HTTP requests to the correct Durable Object instance
 * 2. Upgrades the connection to SSE (Server-Sent Events) for the MCP transport
 * 3. Keeps the session alive across multiple tool calls
 *
 * Claude Code connects to this endpoint via mcp-remote using the worker's
 * deployed URL (e.g. https://mcp-server.<subdomain>.workers.dev/mcp).
 */
export default {
  fetch: DocsAgent.mount("/mcp"),
};