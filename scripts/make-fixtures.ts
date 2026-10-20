import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync, cpSync } from "fs"; import { join, dirname } from "path"; import { tmpdir } from "os"; import { spawnSync } from "child_process";
// Regenerates src/test/resources/bundles/order-desk/ from the plugin's own synthetic test project (Order Desk):
//   SA_SKILLS_DIR=<solutions-architect-skills checkout> bun scripts/make-fixtures.ts src/test/resources/bundles/order-desk
// The project text is copied from the plugin's tests/export-index.test.ts (fixture()), so the bundles are what the plugin writes.
const SA = process.env.SA_SKILLS_DIR; if (!SA) { console.error("set SA_SKILLS_DIR to a solutions-architect-skills checkout"); process.exit(2); }
const { exportIndex, diffBundles, renderChanges } = require(join(SA, "plugins/sa-delivery/skills/architecture-index-export/scripts/export-index.js"));
const E = require(join(SA, "plugins/sa-records/scripts/shared/escalate.js"));
const BASE = mkdtempSync(join(tmpdir(), "sa-index-"));
const P = join(BASE, "order-desk");
const w = (p: string, s: string) => { mkdirSync(dirname(join(P, p)), { recursive: true }); writeFileSync(join(P, p), s); };
const hdr = (self: string, type: string, adrs = "ADR-101") => `<!-- EXPLORER_HEADER\nkey_concepts: orders\ntechnologies: Java\ncomponent_self: ${self}\ncomponent_type: ${type}\nscope: s\nrelated_adrs: ${adrs}\n-->`;
const docHdr = (comps: string) => `<!-- EXPLORER_HEADER\nkey_concepts: orders\ntechnologies: Java\ncomponents: ${comps}\nscope: s\nrelated_adrs: ADR-101\n-->`;
const clean = () => [];
const KIT = "<!-- managed by sa-skills:architecture-dev-handoff -->\n<!-- TEMPLATE_VERSION: 4.3.0 -->";   // the markers kit-check.js requires of every kit part

function fixture(version = "1.3.0") {
  rmSync(P, { recursive: true, force: true });
  w("ARCHITECTURE.md", `<!-- ARCHITECTURE_VERSION: ${version} -->\n<!-- ARCHITECTURE_STATUS: Released -->\n<!-- ARCHITECTURE_RELEASED: 2026-10-01 -->\n<!-- DOC_LANGUAGE: en -->\n\n# Order Desk — Architecture\n\n**Version**: ${version}\n\n## Navigation\n\nOrder Desk takes orders from shops and books them on the ledger.\n`);
  w("docs/01-system-overview.md", `# 1. System Overview\n\n${docHdr("web-app, hub-api")}\n\n## 1. Executive Summary <!-- SECTION: Executive Summary -->\n\nOrder Desk books shop orders.\n\n### Problem Statement <!-- LABEL: Problem Statement -->\n\nShops phone orders in.\n\n### Solution <!-- LABEL: Solution -->\n\nA web app over one API.\n\n\`\`\`mermaid\nflowchart TB\n  A --> B\n\`\`\`\n\nAfter the diagram.\n`);
  w("docs/02-architecture-principles.md", `# 3. Architecture Principles\n\n${docHdr("hub-api")}\n\n## 3. Principles <!-- SECTION: Architecture Principles -->\n\n### 3.1 Idempotent writes <!-- PRINCIPLE: Idempotency -->\n\n**Description:** Every write carries a key.\n\n#### Trade-offs\n\n- retries cost a lookup\n\n### 3.2 Notes\n\nfirst\n\n### 3.2 Notes\n\nsecond\n`);
  w("docs/04-data-flow-patterns.md", `# 6. Data Flow\n\n${docHdr("web-app, hub-api, ledger-store")}\n\n## Phase Catalog <!-- LABEL: Phase Catalog -->\n\n| # | Phase | On critical path? | Owner | Failure mode |\n|---|---|---|---|---|\n| 1 | Accept order | Yes | Web App → Hub API | reject |\n| 2 | Book order | Yes | Hub API → Ledger Store | retry |\n\n#### Phase 1 — Accept order\n\n**Hops**: Web App → Hub API\n**Latency budget (p95)**: 120 ms\n\n#### Phase 2 — Book order\n\n**Hops**: Hub API → Ledger Store\n**Latency budget (p95)**: 80 ms\n\n## Flow Inventory <!-- LABEL: Flow Inventory -->\n\n| Flow | Trigger | Use Case | Latency Target |\n|---|---|---|---|\n| 1. Place order | shop submits | UC-01 | 300 ms |\n\n### Flow 1 — Place order\n\n**Phases**: 1, 2\n**Governed by**: ADR-101\n`);
  w("docs/05-integration-points.md", `# 7. Integration Points\n\n${docHdr("web-app, hub-api, ledger-store")}\n\n## Inventory <!-- SECTION: Integration Points -->\n\n| ID | From | To | Protocol | Pattern |\n|---|---|---|---|---|\n| I-01 | Web App | Hub API | HTTPS/JSON | sync |\n| I-02 | Hub API | Ledger Store | PGWIRE | sync |\n\n### I-01 Web App to Hub API\n\nOrders arrive as JSON with an idempotency key.\n`);
  w("docs/components/README.md", `# Components\n\n| # | Component | File | Type | Technology |\n|---|---|---|---|---|\n`);
  w("docs/components/desk.md", `# Desk\n\n${hdr("desk", "internal-system")}\n\n**C4 Level:** System (L1)\n**Type:** Internal System\n`);
  w("docs/components/desk/01-web-app.md", `# Web App\n\n${hdr("web-app", "web-application")}\n\n**Type:** Web Application\n**Technology:** [React]\n\nThe shop-facing app.\n`);
  w("docs/components/desk/02-hub-api.md", `# Hub API\n\n${hdr("hub-api", "api-service")}\n\n**Type:** API Service\n**Technology:** [Spring Boot]\n**Internal Name:** od-hub-api\n**Component Version:** 1.1.0\n\nTakes orders, books them.\n`);
  w("docs/components/desk/03-ledger-store.md", `# Ledger Store\n\n${hdr("ledger-store", "database")}\n\n**Type:** Database\n**Technology:** [PostgreSQL]\n`);
  w("adr/ADR-101-idempotency-keys.md", `# ADR-101: Idempotency keys on every write\n\n**Status**: Accepted\n**Scope**: Project\n\n## Context\n\nShops retry.\n\n## Decision\n\nA key per write.\n`);
  w("tdr/TDR-001-manual-ledger-fix.md", `---\nstatus: Open\n---\n# TDR-001: Manual ledger fix\n\n## Description\n\nA script fixes ledger rows by hand.\n`);
  w("compliance-docs/CC-001.md", "# Contract\n\nZEBRA-COMPLIANCE-TOKEN\n");
  // the handoff kit: Hub API packed, Web App covered at an older version
  w("handoffs/SYSTEM_RUNTIME_VIEW.md", `# System Runtime View\n\n${KIT}\n\n## Flows <!-- SECTION: Flows -->\n\nflows.\n`);
  w("handoffs/CROSS_CUTTING_STANDARDS.md", `# Cross-Cutting Standards\n\n${KIT}\n\n## Errors <!-- SECTION: Error semantics -->\n\nerrors.\n`);
  w("handoffs/interfaces/web-app→hub-api.md", `# Interface Contract: Web App → Hub API\n\n${KIT}\n\nPOST /orders.\n`);
  w("handoffs/interfaces/hub-api→ledger-store.md", `# Interface Contract: Hub API → Ledger Store\n\n${KIT}\n\n[NOT DOCUMENTED — pool size]\n`);
  w("handoffs/components/02-hub-api.md", `# Hub API\n\n${KIT}\n\n## Acceptance <!-- SECTION: Acceptance -->\n\nan order books once.\n`);
  w("handoffs/components/01-web-app.md", `# Web App\n\n${KIT}\n`);
  w("handoffs/assets/02-hub-api/openapi.yaml", "openapi: 3.1.0\ninfo:\n  title: Hub API\n  version: 1.1.0\npaths: {}\n");
  w("handoffs/assets/02-hub-api/ddl.sql", "CREATE TABLE orders (id uuid primary key);\n");
  w("handoffs/assets/02-hub-api/c4-descriptor.md", "# Hub API\n");
  w("handoffs/assets/02-hub-api/security.md", `# Hub API — Security Obligations\n\n<!-- managed by sa-skills:architecture-dev-handoff — rendered by security-obligations.js from the architecture docs and the security analyses; fix a value there and re-run, never here -->\n\n## Inbound — who may call <!-- SECTION: Inbound -->\n\n| Integration | Caller | Protocol | Authentication | Source |\n|---|---|---|---|---|\n| I-01 | Web App | REST | mTLS through the mesh | STRIDE TB-01 |\n\nAny other caller is rejected.\n\n## Decisions in force <!-- SECTION: Decisions in Force -->\n\n- [ADR-012](../../../adr/ADR-012-service-mesh.md) — Service mesh\n`);
  w("handoffs/components/01-web-app.md".replace("components/01-web-app.md", "assets/01-web-app/security.md"), "# Web App — Security Obligations\n\n<!-- managed by sa-skills:architecture-dev-handoff — rendered by security-obligations.js -->\n");
  w("adr/ADR-012-service-mesh.md", `# ADR-012: Service mesh\n\n**Status**: Accepted\n**Scope**: Institutional\n\n## Decision\n\nmTLS between pods.\n`);
  const e = (file: string, v: string) => ({ payload_hash: "sha256:x", template_version: "4.3.0", schema_version: "4", architecture_version: v, cut_sheet_file: file, assets: ["openapi", "ddl", "c4-descriptor"], doc_language: "en", generated: "2026-10-01T10:00:00Z" });
  w("handoffs/.manifest.json", JSON.stringify({ version: "4", generator: "sa-skills:architecture-dev-handoff", generator_version: "8.143.5",
    run: { ekonte_hash: "sha256:a", ekonte_template_version: "4.3.0", settei_hash: "sha256:a", settei_template_version: "4.3.0", schema_version: "4", architecture_version: version, generated: "2026-10-01T10:00:00Z" },
    seams: { "web-app→hub-api": { payload_hash: "sha256:b", template_version: "4.3.0", schema_version: "4", seam_file: "interfaces/web-app→hub-api.md", related_adrs: ["ADR-101"], generated: "2026-10-01T10:00:00Z" },
      "hub-api→ledger-store": { payload_hash: "sha256:c", template_version: "4.3.0", schema_version: "4", seam_file: "interfaces/hub-api→ledger-store.md", related_adrs: [], generated: "2026-10-01T10:00:00Z" } },
    cut_sheets: { "hub-api": e("components/02-hub-api.md", version), "web-app": e("components/01-web-app.md", "1.2.0") } }));
}
const chunks = (out: string) => readFileSync(join(out, "chunks.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));

const ESC_001 = { title: "Hub API calls the partner ledger with a shared client secret", request: "Can we call the partner ledger from Hub API with the client secret we were given?",
  triggers: ["security-iam"], evidence: [{ trigger: "security-iam", where: "docs/07-security-architecture.md:88", note: "mTLS between services; the change adds a shared secret" }],
  proposal: ["Store the secret in the vault and send it as a header."], questions: ["Is a shared secret acceptable for the partner ledger?"], components: ["Hub API"], requested_by: "RUN team", route: "tier-1" };
const OUT = process.argv[2]; rmSync(OUT, { recursive: true, force: true });
const env = { ...process.env, GIT_CEILING_DIRECTORIES: BASE, GIT_AUTHOR_NAME: "t", GIT_AUTHOR_EMAIL: "t@t", GIT_COMMITTER_NAME: "t", GIT_COMMITTER_EMAIL: "t@t" };
const git = (...a: string[]) => spawnSync("git", a, { cwd: P, env, encoding: "utf8" });
const opts = { kitCheck: clean, projectKey: "order-desk", server: "http://localhost:8080/mcp" };
fixture("1.3.0");
const SAVED = join(BASE, "kit"); cpSync(join(P, "handoffs"), SAVED, { recursive: true }); rmSync(join(P, "handoffs"), { recursive: true });
w(".sa-skills/project.json", JSON.stringify({ support: "run" }) + "\n");   // a RUN-team project: Tier 0 from the first release
git("init", "-q"); git("add", "-A"); git("commit", "-qm", "release 1.3.0"); git("tag", "architecture-v1.3.0");
exportIndex(P, { ...opts, out: join(OUT, "1.3.0-search") });
cpSync(SAVED, join(P, "handoffs"), { recursive: true }); git("add", "-A"); git("commit", "-qm", "kit 1.3.0");
exportIndex(P, { ...opts, out: join(OUT, "1.3.0") });
// 1.4.0: the edits of the what-changed test, released with its kit
const edit = (f: string, from: string, to: string) => w(f, readFileSync(join(P, f), "utf8").replace(from, to));
for (const f of ["ARCHITECTURE.md", "handoffs/.manifest.json"]) edit(f, /1\.3\.0/g as any, "1.4.0");
edit("ARCHITECTURE.md", "**Version**: 1.3.0", "**Version**: 1.4.0");
edit("handoffs/assets/02-hub-api/openapi.yaml", "paths: {}", "paths:\n  /orders: {}");
edit("handoffs/interfaces/hub-api→ledger-store.md", "[NOT DOCUMENTED — pool size]", "Pool of 20 connections.");
edit("docs/04-data-flow-patterns.md", "**Latency budget (p95)**: 80 ms", "**Latency budget (p95)**: 90 ms");
edit("docs/components/desk/02-hub-api.md", "**Technology:** [Spring Boot]", "**Technology:** [Spring Boot], [Kafka]");
edit("docs/05-integration-points.md", "| I-01 | Web App | Hub API | HTTPS/JSON | sync |", "| I-01 | Web App | Hub API | HTTPS/JSON | async |");
E.create(P, ESC_001, { today: "2026-10-17" });   // an open escalation, in escalations_open from 1.4.0 on
git("add", "-A"); git("commit", "-qm", "release 1.4.0"); git("tag", "architecture-v1.4.0");
exportIndex(P, { ...opts, out: join(OUT, "1.4.0"), previous: join(OUT, "1.3.0") });
// 1.4.0 again: the kit regenerated after the tag, Hub API's security obligations moved — a second publish that replaces its pack
edit("handoffs/assets/02-hub-api/security.md", "Any other caller is rejected.", "Any other caller is rejected. Ledger Store calls back over I-02.");
git("add", "-A"); git("commit", "-qm", "kit 1.4.0 regenerated");
exportIndex(P, { ...opts, out: join(OUT, "1.4.0-revised"), previous: join(OUT, "1.4.0") });
// a draft on top of 1.4.0, with what Tier 0 writes merged since the release: a workaround (TDR-002) and a proposed ADR
edit("docs/01-system-overview.md", "Shops phone orders in.", "Shops phone and e-mail orders in.");
w("tdr/TDR-002-retry-by-hand.md", `---\nstatus: Open\n---\n# TDR-002: Retry by hand\n\n## Description\n\nA failed booking is retried by hand.\n`);
w("adr/ADR-102-retry-queue.md", `# ADR-102: A retry queue for bookings\n\n**Status**: Proposed\n**Scope**: Project\n\n## Context\n\nBookings fail.\n\n## Decision\n\nA queue retries them.\n`);
exportIndex(P, { ...opts, out: join(OUT, "draft") });
rmSync(BASE, { recursive: true, force: true });
// what_changed by substance (10.10.4): two copies of 1.4.0-revised whose hub-api parts move only in what a release restamps —
// the version, link targets, line ends, the specification's debt table — and, one per part, in what JavaScript's regular
// expressions keep apart (\b is ASCII, [^)\s] stops at a no-break space, . and ^ know \n \r U+2028 U+2029, never U+0085).
// changes.json and changes.md in 1.5.0-restamped are the plugin's diffBundles and renderChanges of the pair
const STAMPED = join(OUT, "1.4.0-stamped"), RESTAMPED = join(OUT, "1.5.0-restamped");
const DEBT = "### Technical debt <!-- LABEL: Technical Debt -->\n\n| TDR | Title | Status |\n|---|---|---|\n| [TDR-001](tdrs/TDR-001.md) | Manual ledger fix | Open |\n\n";
const STAMP: Record<string, [string, string]> = {   // the part, appended (or edited) in the old and in the new copy
  "spec": ["\nSee [the release](releases/v1.4.0.md), v1.4.0.\n", "\nSee [the release](../moved/releases/v1.5.0.md), v1.5.0.\n"],
  "asset:c4-descriptor.md": ["\r\nversion: é1.4.0 v1.4.0 (1.4.0)\r\n", "\nversion: é1.5.0 v1.5.0 (1.5.0)\n"],
  "interface:web-app→hub-api": ["\nSee [the spec](../spec.md).\n", "\nSee [the spec](../../packed/spec.md).\n"],
  "adr:ADR-101": ["\na\rb\n", "\na\nb\n"],
  "runtime-view": ["\nbuild 1.4.0x\n", "\nbuild 1.5.0x\n"],
  "security": ["\nSee [a](x\u00a0y).\n", "\nSee [a](z\u00a0y).\n"],
  "instructions": ["", "\n#### Debt\u2028x <!-- LABEL: Technical Debt -->\n"],
  "adr:ADR-012": ["\nx\u0085", "\nx\u0085### T <!-- LABEL: Technical Debt -->"],
};
for (const [dir, version, side] of [[STAMPED, "1.4.0", 0], [RESTAMPED, "1.5.0", 1]] as const) {
  cpSync(join(OUT, "1.4.0-revised"), dir, { recursive: true });
  const man = JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8")); man.architecture.version = version; delete man.changes;
  writeFileSync(join(dir, "manifest.json"), JSON.stringify(man, null, 1) + "\n"); rmSync(join(dir, "changes.json"), { force: true });
  const pd = join(dir, "buildpacks", "hub-api"), pack = JSON.parse(readFileSync(join(pd, "pack.json"), "utf8"));
  for (const part of pack.parts) {
    if (!STAMP[part.id]) continue;
    let t = readFileSync(join(pd, part.path), "utf8");
    if (side === 1) t = t.replace(/1\.4\.0/g, "1.5.0");
    if (side === 1 && part.id === "spec") t = t.replace("## Acceptance", DEBT + "## Acceptance");
    writeFileSync(join(pd, part.path), t + STAMP[part.id][side]); part.sha256 = (side ? "b-" : "a-") + part.sha256;
  }
  pack.open_questions = [{ part: "spec", text: `Who owns [the key](${side ? "../moved/" : ""}keys.md) in v${version}?` }, ...(side ? [{ part: "security", text: "Which caller signs?" }] : [])];
  writeFileSync(join(pd, "pack.json"), JSON.stringify(pack, null, 1) + "\n");
}
const substance = diffBundles(STAMPED, RESTAMPED);
writeFileSync(join(RESTAMPED, "changes.json"), JSON.stringify(substance, null, 1) + "\n");
writeFileSync(join(RESTAMPED, "changes.md"), renderChanges(substance));
const hub = substance.components.find((c: any) => c.component === "hub-api");
console.log("restamped", JSON.stringify(hub.restamped), "documents", JSON.stringify(hub.documents), "adrs", JSON.stringify(hub.adrs), "oq", JSON.stringify(hub.open_questions), "action", hub.action_required);
for (const d of ["1.3.0-search", "1.3.0", "1.4.0", "1.4.0-revised", "draft"]) { const m = JSON.parse(readFileSync(join(OUT, d, "manifest.json"), "utf8")); console.log(d, m.channel, m.architecture.version, m.counts.chunks, m.buildpacks.map((b: any) => b.component), m.changes?.action_required ?? ""); }

// the escalation issue as the plugin renders it (escalate.js issueBody, `ESC-?` as the MCP server files it): the contract
// EscalateTest holds the server's port to, byte for byte — and requestOf() reads each back the same with the server's
// fingerprint comment after the body
const RS = "architecture-v1.4.0";
const CASES: Record<string, any> = {
  "order-desk": ESC_001,
  "every-field": { title: "Órdenes en lote — un proveedor nuevo 🧾", request: "We need batch orders from the new partner.\nThey send a CSV | a JSON file.",
    triggers: ["new-integration", "new-technology", "security-iam"],
    evidence: [{ trigger: "new-integration", where: "docs/05-integration-points.md:12", note: "no I-nn row for the partner" }, { trigger: "new-integration", where: "the request", note: "\"from the new partner\"" },
      { trigger: "new-technology", note: "SFTP — not in the technology list" }, { trigger: "security-iam", where: "adr/ADR-012-service-mesh.md:9" }],
    proposal: ["  Add I-03 Partner SFTP → Hub API.  ", "", "A batch endpoint | async"], questions: ["Which team owns the SFTP server?", "Is the CSV signed?"],
    components: ["Hub API", " Ledger Store "], integrations: ["I-01", "I-02"], adrs: ["ADR-012", "ADR-101"], tdrs: ["TDR-001"], requested_by: "  Payments RUN team  ", route: "tier-2" },
  "low-confidence": { title: "Unsure whether the retry budget covers the partner", request: "Does the 300 ms target still hold if we retry twice?", triggers: ["low-confidence"] },
  "text-that-looks-like-markup": { title: "Text the renderer must not end early", route: "tier-1",
    request: "\uFEFF\u00A0 First line.\r\n_Note_: the vendor said so.\n## Context\n**Route**: tier-2.\n<!-- not a comment -->\nRun:\n```bash\ncurl x\n```\nand ````four````.\n\n\u3000 last line\u0085 \u2028\n",
    triggers: ["data-classification", "cross-domain", "regulatory"],
    evidence: [{ trigger: "data-classification", where: "docs/08.md:3", note: "a — b — c" }, { trigger: "cross-domain", where: "x | y" }, { trigger: "regulatory", note: "audit\nscope" }],
    proposal: ["step one \u00A0\n\u3000 step two", "## Open questions for the architect", "<!-- x -->"], questions: ["_Close it with care_", "why\n<!-- c -->", "\u2003 padded \u2003"] },
};
const golden: any[] = [];
for (const [name, req] of Object.entries(CASES)) {
  const p = mkdtempSync(join(tmpdir(), "esc-"));
  E.create(p, req, { today: "2026-10-17" });
  const rec = E.records(p)[0], meta = { ...rec.meta, id: "ESC-?", architecture: RS };
  rmSync(p, { recursive: true, force: true });
  const issue = { title: E.issueTitle({ ...rec, meta }), labels: E.labelsOf({ ...rec, meta }), body: E.issueBody({ ...rec, meta, file: "—" }) };
  const back = E.requestOf({ ...issue, body: issue.body }), marked = E.requestOf({ ...issue, body: issue.body + "<!-- sa-escalation: 0123456789abcdef -->\n" });
  if (JSON.stringify(back) !== JSON.stringify(marked)) throw new Error(`${name}: requestOf reads the fingerprint comment into the request`);
  golden.push({ name, architecture: RS, request: req, issue, read_back: back });
}
const GOLDEN = join(OUT, "..", "..", "escalation", "issues.json");
mkdirSync(dirname(GOLDEN), { recursive: true }); writeFileSync(GOLDEN, JSON.stringify(golden, null, 2) + "\n");
console.log("escalation", golden.map((g) => g.name).join(", "));

// the Tier 0 request issue as the plugin renders it (tier0.js issueTitle, labelsOf, issueBody): the contract Tier0Test
// holds the server's port to, byte for byte; requestOf() must read each back. `refused`: what validate() says of each.
const T = require(join(SA, "scripts/shared/tier0.js"));
const EV = [{ where: "hub-api@abc1234 internal/stock/cache.go:42", note: "a 5-minute in-memory cache" }];
const T0_CASES: Record<string, any> = {
  "tdr-workaround": { kind: "tdr-workaround", title: "Hub API caches the stock lookup", request: "build the stock lookup within its p95", components: ["Hub API"],
    requested_by: "Desk team", evidence: EV, fields: { description: "Hub API caches the stock lookup for 5 minutes.", impact: "A sold-out item shows as available for up to 5 minutes.",
      solutions: ["Push stock events from the warehouse"], owner_technical: "Desk team" } },
  "tdr-status": { kind: "tdr-status", title: "TDR-001 is in progress", request: "replace the manual ledger fix with a job", components: ["Ledger Store"],
    evidence: [{ where: "ledger-jobs@9f00d1e src/fix/Job.java:1" }], fields: { tdr: "TDR-001", to: "In Progress", note: "the job replaces the script" } },
  "adr-proposed": { kind: "adr-proposed", title: "  Retry the ledger booking with backoff  ", request: "book orders when the ledger is slow", components: ["Hub API", " Ledger Store "],
    fields: { context: "The ledger times out under load.", decision: "Retry three times with backoff.", consequences: ["Bookings are later, not lost", "A retry budget per order"], alternatives: [] } },
  "doc-fix": { kind: "doc-fix", title: "Typo in the system overview", request: "the overview says phone orders twice", requested_by: "", components: [],
    fields: { file: "docs/01-system-overview.md", line: 12, before: "Shops phone orders in.", after: "Shops phone their orders in." } },
  "every-edge": { kind: "tdr-workaround", title: "Órdenes en lote — un proveedor nuevo 🧾", components: ["Hub API"], requested_by: "  Payments | RUN  ",
    request: "﻿  We batch the partner's orders.\r\n## Evidence\n- not evidence\n```json\n{}\n```\nand ````four````.\n<!-- sa-tier0 -->\n　 last\u0085  \n",
    evidence: [{ where: "  svc@1 a.go:1 ", note: "x | y — z" }, { note: "a note with no where" }, { where: "   ", note: "blank where" }, { where: "svc@1 b.go:2" }, null],
    fields: { description: "A ````fenced```` text \"quoted\" \\ back\\slash\ttab\u0001\u001f\u007f  é 🧾", impact: "x", solutions: ["one", " two ", "", null],
      owner_technical: 7, driver: "Process", nested: { a: [1, 2.5, -3, 1e21, 1e-7, true, false, null], "": {} }, adrs: ["ADR-101"] } },
};
// an unattended build agent files without the key (10.10.0): no architecture-tier0 label, its own footer; "attended" is the default
T0_CASES["unattended"] = { ...T0_CASES["tdr-workaround"], supervision: "unattended" };
T0_CASES["attended"] = { ...T0_CASES["tdr-status"], supervision: "attended" };
// a kind the architecture lists in tier0.auto (10.12.0): the unattended agent keys it itself — the label, its own footer;
// any other kind, or an attended request, as without it. `auto` is support.tier0_auto, read through list()
const T0_AUTO: Record<string, string[]> = { "unattended-auto": [" doc-fix ", "tdr-status"], "unattended-not-auto": ["tdr-status"], "attended-auto": ["tdr-status"] };
T0_CASES["unattended-auto"] = { ...T0_CASES["tdr-status"], supervision: "unattended",
  evidence: [{ where: "acme/ledger-jobs@9f00d1e5 src/fix/Job.java:1" }], fields: { tdr: "TDR-001", to: "Resolved", note: "the job replaced the script" } };
T0_CASES["unattended-not-auto"] = { ...T0_CASES["tdr-workaround"], supervision: "unattended" };
T0_CASES["attended-auto"] = { ...T0_CASES["tdr-status"] };
const T0_REFUSED: Record<string, any> = {
  "unknown-kind": { kind: " doc-fix", title: "x", request: "y", fields: {} },
  "nothing-filled": { kind: "tdr-workaround", title: "  ", request: " ", evidence: [{ where: " ", note: "n" }], fields: { solutions: [" ", null], description: "　", impact: 0, owner_technical: false } },
  "status-rules": { kind: "tdr-status", title: "t", request: "r", evidence: [{ where: "w" }], fields: { tdr: "TDR-1", to: "Done", note: "n" } },
  "planned-date": { kind: "tdr-status", title: "t", request: "r", evidence: [{ where: "w" }], fields: { tdr: "TDR-001", to: "Planned", note: "n", target_date: "2026-1-5" } },
  "planned-date-newline": { kind: "tdr-status", title: "t", request: "r", evidence: [{ where: "w" }], fields: { tdr: "TDR-0001", to: "Planned", note: "n", target_date: "2026-11-05\n" } },
  "wont-fix": { kind: "tdr-status", title: "t", request: "r", evidence: [{ where: "w" }], fields: { tdr: "TDR-001", to: "Won't Fix", note: "n" } },
  "driver": { kind: "tdr-workaround", title: "t", request: "r", evidence: EV, fields: { description: "d", impact: "i", solutions: ["s"], owner_technical: "o", driver: "Budget" } },
  "doc-fix-rules": { kind: "doc-fix", title: "t", request: "r", fields: { file: "docs/../ARCHITECTURE.md", line: "1.5", before: "same", after: "same" } },
  "doc-fix-lines": { kind: "doc-fix", title: "t", request: "r", fields: { file: "adr/ADR-101.md", line: 0, before: "a\nb", after: "c" } },
  "doc-fix-line-text": { kind: "doc-fix", title: "t", request: "r", fields: { file: "docs/a.md", line: " 0x10 ", before: 1, after: 1 } },
};
const t0: any = { architecture: RS, cases: [], refused: [] };
for (const [name, req] of Object.entries(T0_CASES)) {
  const errors = T.validate(req); if (errors.length) throw new Error(`${name}: tier0.js refuses it: ${errors.join("; ")}`);
  const auto = T0_AUTO[name];
  const issue = { title: T.issueTitle(req), labels: T.labelsOf(req, auto), body: T.issueBody({ ...req, architecture: RS }, auto) };
  const back = T.requestOf(issue);
  if (JSON.stringify(back.fields) !== JSON.stringify(req.fields) || back.kind !== req.kind) throw new Error(`${name}: requestOf does not read the request back`);
  t0.cases.push({ name, ...(auto ? { auto } : {}), request: req, issue, read_back: back });
}
for (const [name, req] of Object.entries(T0_REFUSED)) t0.refused.push({ name, request: req, errors: T.validate(req) });
const T0_GOLDEN = join(OUT, "..", "..", "tier0", "issues.json");
mkdirSync(dirname(T0_GOLDEN), { recursive: true }); writeFileSync(T0_GOLDEN, JSON.stringify(t0, null, 2) + "\n");
console.log("tier0", t0.cases.map((g: any) => g.name).join(", "), "· refused", t0.refused.map((g: any) => g.name).join(", "));
