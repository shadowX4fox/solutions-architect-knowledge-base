# solutions-architect-knowledge-base

MCP server for querying and managing solution architecture documentation — powered by Cloudflare Workers, R2, Vectorize, and Workers AI.

## Purpose

This project is a remotely-hosted MCP server that acts as the knowledge layer for a Solution Architect's work. Each "solution" is a collection of markdown documents produced by a Solution Architecture skill (Skill 1) in Claude Code. The MCP server ingests these documents, chunks and embeds them, and exposes two tools that Claude Code can call natively to list available solutions and search within them.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CLAUDE CODE                                   │
│                                                                      │
│   ┌─────────────────────────┐   ┌─────────────────────────────┐    │
│   │       SKILL 1           │   │   MCP CLIENT (built-in)     │    │
│   │  (Solution Arch skill)  │   │                             │    │
│   │                         │   │  Tools:                     │    │
│   │  generates .md docs     │   │  • list_solutions()         │    │
│   │  uploads to R2 with     │   │  • search_docs(question,    │    │
│   │  solution prefix        │   │      solution?)             │    │
│   └────────────┬────────────┘   └────────────┬────────────────┘    │
└────────────────┼────────────────────────────┼─────────────────────┘
                 │ S3-compatible PUT           │ MCP /sse or /mcp
                 ▼                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│              CLOUDFLARE WORKER  (DocsAgent)                          │
│              solutions-architect-knowledge-base                       │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  MCP SERVER  (McpAgent Durable Object)                       │  │
│   │                                                               │  │
│   │  tool: list_solutions                                         │  │
│   │    → R2 delimiter listing of solution prefixes                │  │
│   │    → returns solution names + doc counts                      │  │
│   │                                                               │  │
│   │  tool: search_docs                                            │  │
│   │    input:  { question: string, solution?: string }            │  │
│   │    1. embed question via Workers AI (bge-base-en-v1.5)        │  │
│   │    2. query Vectorize (filtered by solution if provided)      │  │
│   │    3. build context from top-k chunks                         │  │
│   │    4. generate answer via Anthropic API (Claude Sonnet)       │  │
│   │    → returns answer + source citations                        │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  QUEUE CONSUMER  (R2 event → chunk → embed → upsert)         │  │
│   │                                                               │  │
│   │  Triggered by R2 object-create notifications on .md files     │  │
│   │  1. parse key → extract solution name + filename              │  │
│   │  2. fetch doc text from R2                                    │  │
│   │  3. chunk text (1500 chars, 200 overlap)                      │  │
│   │  4. embed all chunks via Workers AI                           │  │
│   │  5. upsert vectors with metadata to Vectorize                 │  │
│   └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘

External services:
┌──────────────┐  ┌──────────────────┐  ┌──────────────┐
│   R2 Bucket  │  │ Vectorize Index   │  │  Workers AI  │
│ solution-docs│  │ solution-docs-idx │  │ bge-base-en  │
│              │  │                   │  │   -v1.5      │
│ {solution}/  │  │ 768 dims, cosine  │  │              │
│   file.md    │  │ metadata filter:  │  └──────────────┘
│              │  │   solution, source│
└──────────────┘  │   chunkIndex, text│
                  └──────────────────┘
```

## R2 Convention

All documents follow the pattern `{solution-name}/{filename}.md` inside the `solution-docs` R2 bucket:

```
solution-docs/
├── payment-gateway/
│   ├── architecture.md
│   ├── kafka-topology.md
│   └── deployment.md
├── ach-processor/
│   ├── overview.md
│   ├── outbox-pattern.md
│   └── compliance.md
└── iso20022-connector/
    ├── design.md
    └── camunda-integration.md
```

Files at the root level (without a solution prefix) are ignored by the queue consumer. Solution names should be lowercase kebab-case.

## Vectorize Schema

Each chunk is stored as a vector with this metadata:

```json
{
  "solution": "payment-gateway",
  "source": "architecture.md",
  "chunkIndex": 0,
  "text": "the actual chunk content..."
}
```

Vector IDs are deterministic: `{solution}/{source}#${chunkIndex}` (e.g., `payment-gateway/architecture.md#0`). This allows re-indexing a document by deleting old vectors by ID before upserting new ones.

The `solution` metadata field enables filtered vector search — when `search_docs` receives a `solution` parameter, it passes `filter: { solution: "..." }` to Vectorize so only that solution's chunks are considered.

## MCP Tools

### list_solutions

No parameters. Lists all solution doc collections by performing an R2 delimiter-based listing at the `/` boundary. Returns each solution name and how many `.md` files it contains.

Use case: when the user asks a question without specifying which solution, call this first to show what's available.

### search_docs

Parameters:
- `question` (string, required) — the natural language question
- `solution` (string, optional) — scope search to this solution only

When `solution` is omitted, searches across all docs. When provided, applies a metadata filter so only that solution's vectors are matched.

The tool embeds the question, queries Vectorize for top-5 chunks, builds a context window, and calls Claude Sonnet to generate an answer with source citations.

## Project Structure

```
solutions-architect-knowledge-base/
├── workers/
│   ├── mcp-server/               # DocsAgent MCP Worker (Durable Object)
│   │   ├── src/
│   │   │   └── index.ts          # McpAgent DO — list_solutions + search_docs tools
│   │   ├── wrangler.toml         # Bindings: R2_DOCS, VECTORIZE, AI, MCP_OBJECT DO
│   │   ├── package.json
│   │   └── tsconfig.json
│   └── document-embedder/        # R2 event → chunk → embed → Vectorize upsert
│       ├── src/
│       │   └── index.ts          # Queue consumer — processes docs-upload-events
│       ├── wrangler.toml         # Bindings: R2_DOCS, VECTORIZE, AI, Queue consumer
│       ├── package.json
│       └── tsconfig.json
├── INSTRUCTIONS.md
└── CLAUDE.md
```

## Key Dependencies

- `agents` — Cloudflare's MCP agent framework (provides McpAgent base class)
- `@modelcontextprotocol/sdk` — MCP protocol types
- `zod` — schema validation for tool inputs

## Cloudflare Bindings

| Binding | Type | Name/Config |
|---------|------|-------------|
| `R2_DOCS` | R2 Bucket | `solution-docs` |
| `VECTORIZE` | Vectorize Index | `solution-docs-index` (768 dims, cosine) |
| `AI` | Workers AI | bound automatically |
| `MCP_OBJECT` | Durable Object | `DocsAgent` class |
| Queue consumer | Queue | `docs-upload-events` (batch=5, retries=3) |

## Secrets

- `ANTHROPIC_API_KEY` — used by `search_docs` to call Claude Sonnet for answer generation

## Transport

The Worker exposes two MCP transport endpoints:
- `/sse` — legacy SSE transport (works with `mcp-remote` proxy for Claude Code)
- `/mcp` — streamable HTTP (new MCP standard, March 2025)

Claude Code connects via `mcp-remote` as a local proxy:

```json
{
  "mcpServers": {
    "docs-search": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "https://solutions-architect-knowledge-base.<account>.workers.dev/sse"
      ]
    }
  }
}
```

## Setup Commands

```bash
# Create infrastructure
wrangler r2 bucket create solution-docs
wrangler vectorize create solution-docs-index --dimensions 768 --metric cosine
wrangler queues create docs-upload-events

# Set secret
wrangler secret put ANTHROPIC_API_KEY

# Install and deploy
npm install
wrangler deploy

# Wire R2 upload events to queue
wrangler r2 bucket notification create solution-docs \
  --event-type object-create \
  --queue docs-upload-events \
  --suffix .md
```

## Uploading Documents

```bash
# Single doc
wrangler r2 object put solution-docs/payment-gateway/architecture.md \
  --file ./docs/payment-gateway/architecture.md

# Bulk upload a solution
for f in ./docs/payment-gateway/*.md; do
  wrangler r2 object put "solution-docs/payment-gateway/$(basename $f)" --file "$f"
done
```

Each upload triggers the queue consumer automatically: R2 event → chunk → embed → Vectorize upsert.

## Usage Flow

```
User:    "What solutions do you have?"
Claude:  → list_solutions()
         ← Found 3 solutions:
              • payment-gateway  (3 docs)
              • ach-processor    (3 docs)
              • iso20022-connector (2 docs)

User:    "How does the outbox pattern work in ach-processor?"
Claude:  → search_docs({ question: "outbox pattern", solution: "ach-processor" })
         ← Scoped answer citing only ach-processor docs

User:    "Compare deployment approaches across all solutions"
Claude:  → search_docs({ question: "deployment approach" })
         ← Cross-solution answer pulling from all docs
```

## Chunking Strategy

Text is split into 1500-character chunks with 200-character overlap. This is a simple character-based split. Future improvements could include markdown-aware splitting (by heading boundaries) for better semantic coherence.

## Development Notes

- The Durable Object (`DocsAgent`) handles MCP session state and tool registration
- The queue consumer runs separately from the MCP server — it processes R2 events asynchronously
- Vector deletion before upsert ensures re-uploading a doc doesn't create duplicate chunks
- The Anthropic API call in `search_docs` uses a system prompt that instructs Claude to answer strictly from context and cite sources
