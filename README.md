# Architecture Index Server

The architecture MCP server: a developer adds one remote MCP server to VS Code, JetBrains or any MCP client, asks the architecture questions while working, and builds one component from its spec.

It serves the `sa-index/1` bundles that the [solutions-architect-skills](https://github.com/shadowx4fox/solutions-architect-skills) plugin exports (`sa-delivery:architecture-index-export`) and CI publishes (`ci/publish-index.sh`). The plugin produces data only. This server embeds the chunks, keeps them in Qdrant, answers exact questions from the facts ledger, and serves build packs byte for byte.

```
architecture repo ──tag──▶ CI: export-index.js ──POST /bundles/{key}/{version}──▶ server ──▶ Qdrant (dense + sparse)
                                                                                  │  ├──▶ Ollama: EmbeddingGemma 2
IDE (VS Code · JetBrains) ◀──── MCP, Streamable HTTP /mcp ────────────────────────┘  └──▶ bundles on disk (ledger, packs)
```

Stack: Spring Boot 4.1 · Spring AI 2.0 (MCP server, Ollama embedding model) · Qdrant Java client 1.19 · Java 21.

## Tools

Every capability is a tool, because every IDE client supports tools while prompts and resources vary by client.

| Tool | Answers from | Use |
|---|---|---|
| `list_projects` | store | which architectures, versions, drafts and build packs the server holds |
| `get_architecture` | ledger | one version at a glance: systems, components, which have a build pack and why the others do not |
| `get_component` | ledger | type, technology, internal name, its docs/05 integrations, flows and budgets, build pack availability |
| `list_integrations` | ledger | the `I-nn` inventory, whole or naming one component (From/To as written, never a guessed direction) |
| `list_flows` · `get_flow` | ledger | flows by number, name or `UC-nn`, each phase with hops and latency budget |
| `list_decisions` | ledger | ADRs or TDRs, by status |
| `search_architecture` | Qdrant | reasons and explanations: dense + sparse (exact ids such as `ADR-112`, `I-07`) fused by RRF; each hit has file, lines, anchor, version |
| `get_chunk` | bundle | the whole text of a hit |
| `get_build_pack` | pack | the index: parts, ordered plan, interfaces both ways, flows, open questions |
| `get_build_instructions` | pack | `BUILD.md`: the `.architecture.json` binding, the rules, plan and done list |
| `get_build_part` · `get_asset` | pack | one part byte for byte (spec, `asset:openapi.yaml`, `interface:a→b`, `adr:ADR-101` …), paged over 60,000 characters |
| `what_changed` | bundles | what a release changed for a component since the version built against; `action_required` where code must move |

Versions: omitted or `latest` is the newest released version, `draft` is the unreleased draft. A component with no build pack in the asked version is an error that names the reason and the versions that have one. The server never falls back to another version on its own.

## Publishing contract

`ci/publish-index.sh` in the plugin calls it. The pipeline secret `SA_INDEX_TOKEN` is `ARCHINDEX_INGEST_TOKEN` here.

| Request | Answer |
|---|---|
| `GET /bundles/{key}/before/{version}` | 200 `application/gzip`: the newest released bundle older than `version`, which the export diffs against for `changes.json` · 404 none |
| `POST /bundles/{key}/{version}?channel=released\|draft` (gzipped tar) | 201 stored · 200 build packs added to a released version held, or nothing new · 409 its chunks differ from the released version held · 400 the URL does not match the bundle · 413 too large · 422 the bundle fails its check (findings in the body) |
| `GET /bundles` | what is held, per slot, with the indexing state |

The rules:

- A released version is immutable in what search serves. A second publish may only add build packs: the second phase of a release, when the regenerated handoff kit is committed.
- A re-run of the tag job keeps the packs the server already holds.
- A project has one draft. A release of the draft's version or a newer one removes it.
- Every bundle passes the same check as `export-index.js --check` before it is kept: every listed file present with its sha256, nothing unlisted, every chunk's hash, and every pack part.
- Extraction refuses links and any path that leaves the bundle.

## Indexing

- One Qdrant collection for every project. Each point carries a named dense vector (`dense`, cosine, 768) and a named sparse vector (`sparse`, Qdrant's `idf` modifier), with keyword payload indexes on `project`, `slot`, `kind`, `components`, `adrs`, `content_hash`, `doc_language` and `chunk_id`.
- A slot is a released version or the draft. The store keeps no text: a hit resolves against the bundle on disk.
- A chunk whose text the project has already embedded reuses that vector. Chunk ids hold across releases, so a release re-embeds only what it changed. On the fixture, 1.4.0 re-embeds 5 of 22 chunks.
- At start, a released slot whose points are already in Qdrant is taken as ready.
- Indexing runs on one background thread. `search_architecture` reports a slot that is not ready yet, while the ledger and build-pack tools answer for it at once.
- Embedding prompts follow EmbeddingGemma's format: documents as `title: {title} | text: …`, queries as `task: search result | query: …`. Ollama adds none, so both are settings.

## Run

```bash
./mvnw test                                   # 13 tests; no Qdrant or Ollama needed (in-memory index, stand-in model)
./mvnw package -DskipTests
ollama pull embeddinggemma-2:270m             # 270m is the text-only tag; see the note below on MLX
./qdrant                                      # or: docker compose -f deploy/compose.yaml up -d
ARCHINDEX_INGEST_TOKEN=… java -jar target/architecture-index-server-0.1.0-SNAPSHOT.jar
```

**EmbeddingGemma 2 needs Ollama's MLX runtime.** On Ollama 0.40.4 on Linux without it, every `embeddinggemma-2` tag fails to pull with `this model requires MLX support, but the MLX runtime is not available` (checked 2026-10-14). Run it where Ollama has MLX, or set `ARCHINDEX_EMBEDDING_MODEL=embeddinggemma:latest`; the live checks ran on v1. A model switch re-embeds the collection: point `QDRANT_COLLECTION` at a new name.

| Setting | Default | |
|---|---|---|
| `ARCHINDEX_STORE` | `qdrant` | `memory` for development: brute force in process, re-embeds at start |
| `ARCHINDEX_EMBEDDING_MODEL` | `embeddinggemma-2:270m` | Ollama tag. 440m adds vision, 570m audio, 740m both; the retrieval eval picks. `embeddinggemma:latest` (v1, same 768 dimensions and prompts) runs anywhere |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | |
| `QDRANT_HOST` · `QDRANT_GRPC_PORT` · `QDRANT_API_KEY` · `QDRANT_TLS` · `QDRANT_COLLECTION` | `localhost` · `6334` · – · `false` · `architecture` | |
| `ARCHINDEX_INGEST_TOKEN` | unset (open, warned) | bearer token for `/bundles/**` |
| `ARCHINDEX_MCP_TOKEN` | unset (open, warned) | bearer token for `/mcp`, until the identity provider is wired |
| `ARCHINDEX_DATA_DIR` | `data` | bundles on disk |

## In the IDE

VS Code, `.vscode/mcp.json`:

```json
{ "servers": { "architecture": { "type": "http", "url": "https://architecture-mcp.internal/mcp",
  "headers": { "Authorization": "Bearer ${input:architecture-token}" } } },
  "inputs": [ { "id": "architecture-token", "type": "promptString", "description": "Architecture MCP token", "password": true } ] }
```

JetBrains: in the AI Assistant MCP settings, add a Streamable HTTP server with the same URL and header.

Claude Code: `claude mcp add --transport http architecture https://architecture-mcp.internal/mcp --header "Authorization: Bearer $TOKEN"`.

The microservice repository binds itself with `.architecture.json`, which `get_build_instructions` writes for it:

```json
{ "server": "https://architecture-mcp.internal/mcp", "project": "order-desk", "component": "hub-api", "architecture_version": "1.4.0" }
```

## Tests and fixtures

`src/test/resources/bundles/order-desk/` holds four bundles of a synthetic project (Order Desk), exported by the plugin from its own test project:

| Bundle | What it is |
|---|---|
| `1.3.0-search` | phase 1 at the tag |
| `1.3.0` | with the Hub API build pack |
| `1.4.0` | contract, budget, technology and integration changes, plus its `changes.json` |
| `draft` | an unreleased draft |

`BundleTest` holds the Java `what_changed` diff to the plugin's own `changes.json`, and the store to the publish contract. `ServerTest` publishes over HTTP with tokens and calls every tool through the real MCP endpoint.

To regenerate the fixtures after a bundle schema change: `SA_SKILLS_DIR=<plugin checkout> bun scripts/make-fixtures.ts src/test/resources/bundles/order-desk`.

## Open

| Item | State |
|---|---|
| Sign-in through the bank's identity provider, permissions per project | open; bearer tokens until then |
| The bank's service template (skeleton, libraries, CI) | outside the architecture; `BUILD.md` rule 6 points the agent to it |
| Retrieval eval | a few questions mapped to expected chunk ids, recall@5. It picks the EmbeddingGemma 2 tag and decides whether Gemini Embedding 2 is worth sending text out |
| Open questions back to the architect | later; the server stays read-only |
