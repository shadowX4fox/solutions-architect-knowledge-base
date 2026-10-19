# Alia — the architecture MCP server

**Your architecture, served to every developer's IDE: ask it while you work, build a component from its spec, escalate what needs an architect.**

[![ci](https://github.com/shadowX4fox/solutions-architect-knowledge-base/actions/workflows/ci.yml/badge.svg)](https://github.com/shadowX4fox/solutions-architect-knowledge-base/actions/workflows/ci.yml)
[![Image](https://img.shields.io/badge/image-ghcr.io%2Fshadowx4fox%2Falia--mcp--server-2496ed.svg)](https://github.com/shadowX4fox/solutions-architect-knowledge-base/pkgs/container/alia-mcp-server)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f.svg)](https://spring.io/projects/spring-boot)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-purple.svg)](https://modelcontextprotocol.io)
[![Companion plugin](https://img.shields.io/badge/companion-solutions--architect--skills-blue.svg)](https://github.com/shadowX4fox/solutions-architect-skills)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](#contributing)

A developer adds one remote MCP server to VS Code, JetBrains, Claude Code or any MCP client. From then on, their coding
agent can ask the architecture *why* a call is asynchronous, list *exactly* what talks to a service, read a whole ADR, and
build one component from its build pack — every contract byte for byte, every answer citing the version it came from.

Alia serves the `sa-index/1` bundles that the
[Solutions Architect Skills](https://github.com/shadowX4fox/solutions-architect-skills) plugin exports from a versioned
`ARCHITECTURE.md`. The plugin produces data only. This server embeds it, answers exact questions from the facts ledger,
and serves build packs as stored. It is self-hosted: no architecture text leaves your network.

```
architecture repo ──tag──▶ CI: export-index.js ──POST /bundles/{key}/{version}──▶ server ──▶ Qdrant (dense + sparse)
                                                                                  │  ├──▶ Ollama: Qwen3-Embedding 0.6B
IDE (VS Code · JetBrains) ◀──── MCP, Streamable HTTP /mcp ────────────────────────┘  └──▶ bundles on disk (ledger, packs)
```

---

## Contents

- [Why](#why) · [Quick start](#quick-start) · [Connect your IDE](#connect-your-ide) · [Tools](#tools)
- [How it works](#how-it-works) — publishing, downloads, indexing, escalations
- [Deploy](#deploy) · [Configuration](#configuration)
- [Development](#development) · [Roadmap](#roadmap)
- [Contributing](#contributing) · [Security](#security) · [License](#license)

---

## Why

Coding agents guess when they have nothing to read. An architecture they cannot query becomes a guess too: an invented
timeout, a renamed field, an endpoint left open "for now". Alia gives the agent the architecture itself:

- **Exact answers where exactness matters.** Lists and relations — components, integrations, flows, ADRs, TDRs — come
  from the facts ledger, complete, never from search. A component is matched by exact name, never partially.
- **Search for the reasons.** Hybrid retrieval (meaning plus exact identifiers such as `ADR-112` or `I-07`) for the
  *why*, each hit citing its file, lines and version.
- **Contracts are never paraphrased.** OpenAPI, AsyncAPI, DDL and every build pack part are served byte for byte, with
  their `sha256`, and downloadable with `curl` so the model never retypes them.
- **Versioned like code.** Released versions are immutable; a draft is separate; `what_changed` tells a service exactly
  what moved since the version and the pack revision it was built from.
- **A human in the loop where it counts.** For maintenance (RUN) teams, a change that crosses an escalation trigger is
  never applied: it becomes an issue for an architect, filed only after the user said yes.

---

## Quick start

You need Docker. The stack is the server, [Qdrant](https://qdrant.tech) and [Ollama](https://ollama.com).

```bash
git clone https://github.com/shadowX4fox/solutions-architect-knowledge-base.git
cd solutions-architect-knowledge-base

export ARCHINDEX_INGEST_TOKEN=change-me-ci ARCHINDEX_MCP_TOKEN=change-me-ide
docker compose -f deploy/compose.yaml up -d
docker compose -f deploy/compose.yaml exec ollama ollama pull qwen3-embedding:0.6b

# publish the synthetic Order Desk example that ships with the tests
tar -czf - -C src/test/resources/bundles/order-desk/1.4.0 . | curl -H "Authorization: Bearer $ARCHINDEX_INGEST_TOKEN" \
  -H "Content-Type: application/gzip" --data-binary @- "http://localhost:8080/bundles/order-desk/1.4.0?channel=released"
```

Then [connect your IDE](#connect-your-ide) to `http://localhost:8080/mcp` with the MCP token, and ask: *"What talks to
Hub API in order-desk?"*

For your own architecture, document it with the plugin and let CI publish each release: the plugin's
`sa-delivery:architecture-index-export` skill and its `ci/publish-index.sh` do the export and the upload.

**The published image.** CI publishes `ghcr.io/shadowx4fox/alia-mcp-server` for amd64 and arm64: `edge` from `main`,
the version and `latest` from a `v*` tag, and a `sha-…` tag for every build. It needs Ollama, and Qdrant unless
`ARCHINDEX_STORE=file`:

```bash
docker run -d -p 8080:8080 -v alia-data:/data --add-host=host.docker.internal:host-gateway \
  -e ARCHINDEX_STORE=file -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -e ARCHINDEX_INGEST_TOKEN=change-me-ci -e ARCHINDEX_MCP_TOKEN=change-me-ide ghcr.io/shadowx4fox/alia-mcp-server:edge
```

**Without Docker:** `./mvnw package -DskipTests`, `ollama pull qwen3-embedding:0.6b`, then
`ARCHINDEX_STORE=file ARCHINDEX_INGEST_TOKEN=… java -jar target/architecture-index-server-*.jar` — the `file` engine
needs no Qdrant (see [Deploy](#deploy)).

---

## Connect your IDE

**VS Code**, `.vscode/mcp.json`:

```json
{ "servers": { "alia": { "type": "http", "url": "https://alia-mcp.internal/mcp",
  "headers": { "Authorization": "Bearer ${input:alia-token}" } } },
  "inputs": [ { "id": "alia-token", "type": "promptString", "description": "Alia MCP token", "password": true } ] }
```

**JetBrains:** in the AI Assistant MCP settings, add a Streamable HTTP server with the same URL and header.

**Claude Code:** `claude mcp add --transport http alia https://alia-mcp.internal/mcp --header "Authorization: Bearer $TOKEN"`.

A service repository binds itself to the architecture with `.architecture.json`, which `get_build_instructions` writes:

```json
{ "server": "https://alia-mcp.internal/mcp", "project": "order-desk", "component": "hub-api", "architecture_version": "1.4.0" }
```

---

## Tools

Every capability is a tool, because every IDE client supports tools, while support for prompts and resources varies.

| Tool | Answers from | Use |
|---|---|---|
| `list_projects` | store | which architectures, versions, drafts and build packs the server holds |
| `get_architecture` | ledger | one version at a glance: systems, components, which have a build pack and why the others do not; `support`, the RUN-team policy |
| `get_component` | ledger | type, technology, internal name, its integrations, flows and budgets, build pack availability |
| `list_integrations` | ledger | the `I-nn` inventory, whole or naming one component (From/To as written, never a guessed direction) |
| `list_flows` · `get_flow` | ledger | flows by number, name or `UC-nn`, each phase with hops and latency budget |
| `list_decisions` | ledger | ADRs or TDRs, by status |
| `search_architecture` | vector index | reasons and explanations: dense + sparse fused by weighted RRF; each hit has file, lines, anchor, version |
| `get_chunk` | bundle | the whole text of a hit |
| `get_document` | bundle | a whole document (by file, ADR/TDR id or component) rebuilt from its chunks in line order |
| `get_build_pack` | pack | the index: parts (each with its `sha256` and `download_url`), ordered plan, interfaces both ways, flows, open questions |
| `get_build_instructions` | pack | `BUILD.md`: the `.architecture.json` binding, the rules, the plan and the done list |
| `get_build_part` · `get_asset` | pack | one part byte for byte (spec, `asset:openapi.yaml`, `interface:a→b`, `adr:ADR-101` …), paged over 60,000 characters |
| `what_changed` | bundles | what changed for a component since the version and `pack_revision` it was built against, its technical debt included (the TDRs its descriptor names, never an action); `action_required` where code must move |
| `preview_escalation` | release, ledger | a change that crosses a trigger, or a question only the architect can answer that blocks the build, as the issue it would open (`[ESC-?] …`), with the commands that file it; writes nothing |
| `record_escalation` | — | the filed issue's URL, logged against its preview |
| `preview_tier0` | release, ledger | a Tier 0 write (`tdr-workaround`, `tdr-status`, `adr-proposed`, `doc-fix`) requested, as the issue it would open (`[T0-?] …`, labels `architecture-tier0`, `tier0:<kind>`), with the commands that file it; the architecture repository writes the record. Writes nothing |
| `record_tier0` | — | the filed request's URL, logged against its preview |

Versions: omitted or `latest` is the newest released version; `draft` is the unreleased draft. A component with no build
pack in the asked version is an error naming the reason and the versions that have one. The server never falls back to
another version on its own.

---

## How it works

### Publishing

The plugin's `ci/publish-index.sh` calls this contract. Its pipeline secret `SA_INDEX_TOKEN` is `ARCHINDEX_INGEST_TOKEN` here.

| Request | Answer |
|---|---|
| `GET /bundles/{key}/before/{version}` | 200 `application/gzip`: the newest released bundle older than `version`, which the export diffs against for `changes.json` · 404 none |
| `POST /bundles/{key}/{version}?channel=released\|draft` (gzipped tar) | 201 stored · 200 a released version held, the body naming the build packs `added`, `replaced` and `unchanged` · 409 refused, `reason` `chunks_differ`, `ledger_differs` or `packs_missing` · 400 the URL does not match the bundle · 413 too large · 422 the bundle fails its check |
| `GET /bundles` | what is held, per slot, with the indexing state |

- **A released version is immutable** in what search serves. A second publish of it — the second phase of a release, when
  the regenerated handoff kit is committed — carries the same chunks, the same ledger and every build pack held. It may
  add packs and replace them, never drop one. Anything else is refused and what is held stays.
- **A pack is replaced when its revision moved:** the `sha256` the manifest lists for its `pack.json`, which lists every
  part's hash. `get_build_pack` returns it as `pack_revision`.
- The ledger is compared exactly, without the clock an export before plugin v8.150.1 wrote into it. Pin `SA_PLUGIN_REF`
  so both publishes of a release run the same plugin. A re-run of the same job changes nothing.
- Each replacement is kept under `{data}/bundles/{key}/revisions/{version}.json`, so `what_changed` with `from_revision`
  lists the replacements since that revision, then the diff to the newer version.
- A project has one draft; a release of the draft's version or a newer one removes it.
- Every bundle passes the same check as `export-index.js --check` before it is kept: every listed file with its sha256,
  nothing unlisted, every chunk's hash, every pack part. Extraction refuses links and paths that leave the bundle.

### Downloading a part

An agent saves a contract asset with curl, so the model never retypes it and git shows the contract diff.
`get_build_pack` lists a `download_url` on every part when `ARCHINDEX_PUBLIC_URL` is set.

| Request | Answer |
|---|---|
| `GET /packs/{key}/{version}/{slug}/{path}[?pack_revision=…]` | 200 the file as stored, `ETag: "<sha256>"`, `X-Pack-Revision` · 304 `If-None-Match` matched · 404 no such project, version, pack or part · 409 `pack_revision` is not the current one |

```bash
curl -fsSL -H "Authorization: Bearer $ALIA_TOKEN" -o architecture/openapi.yaml "$DOWNLOAD_URL?pack_revision=$PACK_REVISION"
sha256sum architecture/openapi.yaml
```

- `{version}` is a released version, `latest` or `draft`; `{path}` is the part's `path` in `pack.json`, and nothing else
  in the bundle is served. It takes the MCP token, never the pipeline's.
- Only the current revision is served. A 409 sends the agent back to `get_build_pack` and `what_changed`.
- Without a download, `get_build_part`'s pages joined in order are the stored bytes: no page ends inside a surrogate
  pair, and nothing is trimmed or normalized.

### Indexing

- One Qdrant collection for every project. Each point carries a named dense vector (`dense`, cosine, 1,024) and a named
  sparse vector (`sparse`, Qdrant's `idf` modifier), with keyword payload indexes on `project`, `slot`, `kind`,
  `components`, `adrs`, `content_hash`, `doc_language` and `chunk_id`.
- A slot is a released version or the draft. The index keeps no text: a hit resolves against the bundle on disk.
- A chunk whose text the project has already embedded reuses its vector, and chunk ids hold across releases, so a release
  re-embeds only what it changed.
- Indexing runs on one background thread. `search_architecture` reports a slot not ready yet, while the ledger and
  build-pack tools answer for it at once.
- The fusion weighs the dense list 1 and the sparse list `ARCHINDEX_SPARSE_WEIGHT` (0.3): at equal weight the exact-term
  list cost ordinary questions their best hit; at 0.3 identifiers still come first ([eval/README.md](eval/README.md)).
- Embedding prompts are the model's: Qwen3-Embedding takes an instruction on the query and nothing on documents.
- The context window Ollama reads (`ARCHINDEX_EMBEDDING_CONTEXT`, 8192) is sent on every request: without it Ollama
  loads the model at 4096 and cuts a long chunk silently.
- A collection created for one model refuses a model of another dimension. A model switch takes a new `QDRANT_COLLECTION`.

### Escalations

A RUN team (`support.mode` is `run` in the release's manifest, plugin v10.3.0 and later) asks the released architecture
from the IDE. The four writes under `support.tier0_writes` need no architect, but they belong in the architecture
repository, not the service one: the agent requests them with `preview_tier0` (see [Tier 0 requests](#tier-0-requests)).
A change that crosses one of `support.triggers` is not applied: it becomes an escalation for a human architect, filed as an issue on the
architecture repository's git host. So does a question that blocks the build and only the architect can answer: a
contract with nothing to implement, an interface whose authentication is not documented, a `Proposed` decision the code
depends on. The agent sends the questions in `questions`, the trigger each one touches (an authentication or
authorization gap is `security-iam`; a gap no other trigger names is `low-confidence`) with its evidence (the build-pack
part and the line), and route `tier-1`. On a project with no `archindex.escalation`, it hands the user the same
questions, each with its part and line, for the architects. The flow mirrors the plugin's `escalate.js`:

1. **`preview_escalation`** checks the request against the latest release only, never a draft, and only for a RUN-team
   project with a release. It validates the request as `escalate.js validate` does: an unknown trigger, a trigger with no
   evidence (`low-confidence` aside) or an unknown route is refused. Components go under their canonical names. It
   returns the issue as `escalate.js` renders it, the release's open escalations, and `file_with`: the commands for the
   project's host (`gh`, `glab`, `az boards` or Gitea's API). Nothing is written.
2. **The agent shows the user the issue.** Only after a yes does it file it with the developer's own access. It first
   checks the open escalation issues for the `preview_id`, so a preview filed twice is found.
3. **`record_escalation`** logs the issue's URL against its `preview_id`.

The server calls no git host and holds no token. It names where each project files (`archindex.escalation.projects`);
the caller never chooses. The issue is titled `[ESC-?]`: the plugin's `escalate.js import` writes the record into the
architecture repository and numbers it. The `preview_id` is the issue's fingerprint, carried in the body as a comment.
The log `archindex.escalation` has one line per preview (project, route, triggers, who asked) and one per filing (the
URL), joined on the `preview_id` — the count of escalations per team, with no store behind it.

### Tier 0 requests

From plugin v10.7.0 a RUN-team release lists its Tier 0 kinds (`support.tier0_kinds`, each with its label, its required
fields and whether it needs evidence) and the TDR status moves (`support.tdr_moves`); from v10.7.1 it also names the TDR
statuses and drivers (`support.tdr_statuses`, `support.tdr_drivers`) — a release exported with v10.7.0 is read with that
version's lists. A workaround recorded as a TDR
(`tdr-workaround`), a TDR status move (`tdr-status`), a project ADR in `Proposed` (`adr-proposed`) or a doc fix that
changes no fact (`doc-fix`) is requested, never made from the service repository:

1. **`preview_tier0`** checks the request against the latest release as the plugin's `tier0.js prepare` would:
   `validate()` with its own words (the kind, its fields, the evidence, a TDR id, a status, a `YYYY-MM-DD` date, a file
   under `docs/`, a line number), then every component by canonical name, a workaround's ADRs and a status move's TDR
   and arrow on the release's ledger. A move to `Won't Fix` is always refused: it is the architect's decision. It returns
   the issue exactly as `tier0.js render` writes it (`[T0-?] …`, labels `architecture-tier0` and `tier0:<kind>`, the
   fields as a JSON block) and `file_with`, the same commands as an escalation's.
2. **The agent shows the user the issue** and files it only after a yes.
3. **`record_tier0`** logs the URL against the `preview_id` (log `archindex.tier0`).

The architecture repository writes the record (`architecture-tier0.yml`), checking the request again; one that is no
Tier 0 write is refused there, with the reason. The body is the plugin's byte for byte, so it carries no fingerprint:
the agent finds a request filed already by its title and body.

### Unattended builds

A build no person attends — a CI job, a platform's build agent — declares `"supervision": "unattended"` in the service
repository's `.architecture.json` (plugin v10.10.0), and its agent passes `supervision: "unattended"` to both previews
(`"attended"`, the default, otherwise; any other value is refused). Nobody says yes, so:

- **`preview_tier0`** renders the issue as `tier0.js` does for an unattended request: the label `tier0:<kind>` only,
  never `architecture-tier0`, and a footer saying a person reviews it and applies the label — that is the approval.
  `file_with` adds no `architecture-tier0`, and the agent never applies it. The architecture repository refuses the label
  from a bot or an account in `SA_TIER0_AGENTS` regardless.
- **`preview_escalation`** renders the same issue: an escalation writes nothing, so it is filed without a yes.
- **Either way,** `file_with` is followed with the build's own identity, and the issue is listed in the build's pull
  request, where a person reviews the work.

---

## Deploy

### Engines

`ARCHINDEX_STORE` picks the engine behind `search_architecture`; only Ollama is needed besides it.

| Engine | Where the vectors live | At start | For |
|---|---|---|---|
| `qdrant` | the Qdrant collection | a released slot already held is ready | a shared server |
| `file` | one file, `<data-dir>/index/vectors-<dimensions>.bin` | read from the file | one machine, local runs |
| `memory` | the process | everything re-embedded | tests and throwaway runs |

`file` and `memory` search in process by brute force with the same fusion as Qdrant.

```bash
docker compose -f deploy/compose.yaml up -d                              # Qdrant engine: server, qdrant, ollama
docker compose -f deploy/compose.yaml --profile file up -d server-file   # file engine: no Qdrant
docker compose -f deploy/compose.yaml --profile scale up -d              # plus a reader on 8081
```

### Several instances

For a shared server, run the `qdrant` engine. One instance writes and any number read; all share the data directory (a
ReadWriteMany volume, or one volume on one host) and the Qdrant collection.

| | `ARCHINDEX_ROLE=all` (the writer, exactly one) | `ARCHINDEX_ROLE=reader` (any number) |
|---|---|---|
| `POST /bundles` | publishes | 403: publish to the writer |
| indexing | embeds, writes to Qdrant, records each slot's chunk set | never embeds or writes; a slot is ready when the writer's record names its chunk set |
| bundles | writes a new directory per publish, then `HEAD.json`, then `.generation` | reloads the projects that changed when `.generation` moves |
| `/mcp`, `/packs` | answers | answers |

- **MCP is stateless.** No session is held in memory, so any instance answers any call behind a load balancer.
- **A bundle directory never changes once written.** A replaced one stays for `ARCHINDEX_RETAIN_REPLACED` (10 minutes),
  longer than a reader takes to reload, then the writer deletes it.
- **Search never sees half a slot.** A re-index writes the new chunk set over the points held, then removes the rest.
- **One writer.** Publishes are rare, so the writer is a single replica: two writers on one data directory would
  overwrite each other's `HEAD.json`.
- **Ollama has no state.** Run replicas behind their own load balancer, all with the same model.

Three URLs, each with a fixed target:

| URL | Set in | With a load balancer | Without one |
|---|---|---|---|
| publish, `/bundles` | CI: `SA_INDEX_SERVER` | the writer | the writer's address; a reader answers 403 |
| MCP, `/mcp` | the IDE, and `server` in each `.architecture.json` | the load balancer | any instance |
| download base, `ARCHINDEX_PUBLIC_URL` | each instance's environment | the load balancer | each instance's own address |

`ARCHINDEX_PUBLIC_URL` must resolve on the developer's machine, where curl runs — never a container hostname. Make
`.architecture.json`'s `server` a stable DNS name you can repoint.

### Model and hardware

`qwen3-embedding:0.6b` is the default: 32K context (every chunk the export cuts fits), multilingual, Apache 2.0, 1,024
dimensions. On the [retrieval eval](eval/README.md) it ties the other candidates on ordinary questions and leads on
answers deep in long chunks.

Ollama uses the GPU on its own: CUDA, or ROCm on AMD. On an AMD Radeon RX 9060 XT it embeds about 19 chunks a second; on
a CPU, about 1. A release re-embeds only the chunks it changed. EmbeddingGemma 2 needs Ollama's MLX runtime, which the
Linux package does not ship (checked with Ollama 0.40.4).

---

## Configuration

| Setting | Default | |
|---|---|---|
| `ARCHINDEX_INGEST_TOKEN` | unset (open, warned) | bearer token for `/bundles/**` |
| `ARCHINDEX_MCP_TOKEN` | unset (open, warned) | bearer token for `/mcp` and `/packs/**` |
| `ARCHINDEX_PUBLIC_URL` | unset (no `download_url`) | the base the IDE reaches the server at, e.g. `https://alia-mcp.internal` |
| `ARCHINDEX_DATA_DIR` | `data` | bundles on disk; shared by every instance |
| `ARCHINDEX_ROLE` | `all` | `all`: publishes, indexes and answers · `reader`: answers only, follows the writer; needs `qdrant` |
| `ARCHINDEX_REFRESH_INTERVAL` | `5s` | how often a reader looks for the writer's publishes |
| `ARCHINDEX_RETAIN_REPLACED` | `10m` | how long a replaced bundle directory stays |
| `ARCHINDEX_STORE` | `qdrant` | `qdrant` · `file` · `memory` |
| `ARCHINDEX_FILE_PATH` | `<data-dir>/index/vectors-<dimensions>.bin` | the `file` engine's file |
| `ARCHINDEX_EMBEDDING_MODEL` | `qwen3-embedding:0.6b` | Ollama tag; another model needs its prompts (`ARCHINDEX_EMBEDDING_QUERYPREFIX`, `…_DOCUMENTPREFIX`), its dimensions and a new collection |
| `ARCHINDEX_EMBEDDING_DIMENSIONS` | `1024` | the model's output size |
| `ARCHINDEX_EMBEDDING_CONTEXT` | `8192` | tokens Ollama reads per chunk |
| `ARCHINDEX_SPARSE_WEIGHT` | `0.3` | the sparse list's weight in the fusion |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | |
| `QDRANT_HOST` · `QDRANT_GRPC_PORT` · `QDRANT_API_KEY` · `QDRANT_TLS` · `QDRANT_COLLECTION` | `localhost` · `6334` · – · `false` · `architecture-qwen3-0.6b-1024` | |

Where each project's escalations are filed, in `application.yaml` or the equivalent environment variables
(`ARCHINDEX_ESCALATION_PROJECTS_0_PROJECT`, …):

```yaml
archindex:
  escalation:
    projects:
      - project: order-desk
        host: github                      # github | gitlab | gitea | azure
        repository: acme/order-desk-architecture   # GitLab: the project path · Azure DevOps: organization/project
        server: github.acme.internal      # unset: github.com, gitlab.com, dev.azure.com · Gitea: required
        work-item-type: Issue             # Azure DevOps only
```

A project without an entry is not escalated through the server: the agent sends the user to the architects directly.

---

## Development

Spring Boot 4.1 · Spring AI 2.0 (MCP server, Ollama embedding model) · Qdrant Java client 1.19 · Jackson 3 · Java 21.

```bash
./mvnw test                      # every test; in-memory index and a stand-in embedding model, no services needed
QDRANT_TEST_HOST=localhost QDRANT_TEST_PORT=6334 ./mvnw test -Dtest=QdrantTest   # the indexer against a real Qdrant
./mvnw package -DskipTests       # target/architecture-index-server-*.jar
```

CI (`.github/workflows/ci.yml`) runs every test on each push and pull request, builds the image and smoke-tests it: the
container starts, takes the Order Desk bundle and answers `list_projects` over MCP. Only then does it publish, from `main`
and from `v*` tags; a pull request builds without publishing.

### Fixtures

`src/test/resources/bundles/order-desk/` holds five bundles of a synthetic project, *Order Desk*, exported by the plugin
from its own test project:

| Bundle | What it is |
|---|---|
| `1.3.0-search` | the release tag, before the handoff kit |
| `1.3.0` | with the Hub API build pack |
| `1.4.0` | contract, budget, technology and integration changes, its `changes.json`, an open escalation |
| `1.4.0-revised` | 1.4.0 with the Hub API pack regenerated: its security obligations moved |
| `draft` | an unreleased draft |

`src/test/resources/escalation/issues.json` holds the escalation issues the plugin renders for four requests, the
awkward text included. `src/test/resources/tier0/issues.json` holds the Tier 0 issues `tier0.js` renders, one per kind
and one with every edge (backticks, pipes, padding, numbers, control characters), and what its `validate()` refuses.

| Test | Holds |
|---|---|
| `BundleTest` | the Java `what_changed` diff to the plugin's own `changes.json`; the store to the publishing contract |
| `EscalateTest` | the Java escalation issue to the plugin's, byte for byte |
| `EscalationTest` | the RUN-team policy gates and each host's filing commands |
| `Tier0Test` | the Java Tier 0 issue and refusals to the plugin's, byte for byte; JavaScript's numbers and key order; the checks on the ledger |
| `ServerTest` | every tool through the real MCP endpoint, publishing over HTTP with tokens, every part downloaded |
| `BuildPackTest` | a part's pages to its stored bytes |
| `ScaleTest` | a writer and a reader on one data directory and one index |
| `QdrantTest` | the indexer and a reader's view against a real Qdrant |

Regenerate the fixtures after a bundle schema change, or a change to the plugin's escalation or Tier 0 rendering:

```bash
SA_SKILLS_DIR=<solutions-architect-skills checkout> bun scripts/make-fixtures.ts src/test/resources/bundles/order-desk
```

### Retrieval eval

[`eval/`](eval/README.md) measures an embedding model the way the server ranks — hit@1 and hit@5 on ordinary questions,
answers deep in long chunks, and exact identifiers. Run it before changing the model, its prompts or the sparse weight.

---

## Roadmap

| Item | State |
|---|---|
| Sign-in through an identity provider, permissions per project | open; bearer tokens until then |
| A service template (skeleton, libraries, CI) | outside the architecture; `BUILD.md` points the agent to the organization's own |
| Retrieval eval on a real architecture | `eval/` measures on a synthetic one; confirm the model and the sparse weight on yours |
| Bundles on blob storage (S3-compatible, Azure Blob) for Kubernetes | deferred until Kubernetes is the target ([#12](https://github.com/shadowX4fox/solutions-architect-knowledge-base/issues/12)); a volume until then, ReadWriteMany for readers across nodes |

---

## Contributing

Pull requests are welcome. Bugs and ideas go to [Issues](https://github.com/shadowX4fox/solutions-architect-knowledge-base/issues).
`./mvnw test` must pass before a PR. A change is held to these rules — [CLAUDE.md](CLAUDE.md) carries them in full:

- **The plugin owns the bundle schema and the publishing contract.** This server reads `sa-index/1` and never redefines
  it. A schema change starts in [the plugin](https://github.com/shadowX4fox/solutions-architect-skills), then the fixtures
  are regenerated here.
- **Ports stay ports.** `BundleChecker`, `BundleDiff` and `Escalate` are the plugin's own functions, field for field, each
  held to the plugin's output by a test.
- **Lists and relations come from the ledger, never from search.** A build never goes through embeddings.
- **Fixtures are synthetic.** Never commit a bundle exported from a real organization's architecture.
- **Commits** are scoped Conventional Commits — `<type>(<scope>): <description>`, `!` for a breaking change.

## Security

Report a vulnerability privately through GitHub's **Report a vulnerability** (Security → Advisories) rather than in a
public issue, with what is affected, how to reproduce it and its impact.

The server is meant to run inside your network: set `ARCHINDEX_INGEST_TOKEN` and `ARCHINDEX_MCP_TOKEN` (it warns when
either is missing), keep them out of versioned files, and put TLS in front of it. It calls no external service besides
your Ollama and Qdrant.

## License

[MIT](LICENSE) © 2026 shadowx4fox.

Dependencies, container images and the embedding model carry their own licenses: Spring Boot and Spring AI (Apache 2.0),
the Qdrant client and Qdrant (Apache 2.0), Ollama (MIT), Qwen3-Embedding (Apache 2.0).
