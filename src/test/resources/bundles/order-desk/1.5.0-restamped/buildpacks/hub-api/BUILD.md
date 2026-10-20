# Build Hub API

You are building **Hub API** (`od-hub-api`) — API Service, Spring Boot, Kafka — as specified by the architecture **Order Desk**, version **1.5.0**. Everything you need is in this build pack. Fetch each part with `get_build_part(project: "order-desk", component: "hub-api", part: "<part id>")`; ask the architecture questions with `search_architecture` while you work.

## Bind the repository first

Write `.architecture.json` at the root of the repository, or check the one there names this component and this version. Call `get_build_pack(project: "order-desk", component: "hub-api")` and replace the `pack_revision` placeholder with the `pack_revision` it returns — the revision of this pack you are building from:

```json
{
  "server": "http://localhost:8080/mcp",
  "project": "order-desk",
  "component": "hub-api",
  "architecture_version": "1.5.0",
  "pack_revision": "<pack_revision from get_build_pack>"
}
```

**Nobody attending this build** — a CI job, a platform's build agent — is declared in this file: `"supervision": "unattended"` (absent, a person works with you). Then nobody answers a question or says yes: the rules below say what changes, and your work ends in a pull request whose description lists, under **Architecture requests**, every `TODO(architecture)` you left and every issue you filed, with its URL — the person who reviews it reads them there.

When the architecture releases a new version, or this pack is revised within its version (`get_build_pack` returns another `pack_revision`), call `what_changed(project: "order-desk", component: "hub-api", from: "<the version in this file>", from_revision: "<the pack_revision in this file>", to: "<the new version>")`. Overwrite the copies under `architecture/` with the new assets first (delete the ones it removes) and commit them alone — `architecture: contracts <new version> (pack_revision <new revision>)` — so the IDE and the pull request show what the contract changed before any code moves; then update the service, then this file.

## Rules

1. **Contracts are implemented exactly.** Every asset under `asset:` is the contract: paths, operations, messages, fields, types, tables, keys and their names as written. Keep each one, byte for byte, at `architecture/<file>` in the repository — the file name as the part gives it — and generate code from that copy where a generator exists. Copy the bytes, never retype them: when `get_build_pack` lists a `download_url` for the part, download it with the MCP server's bearer token and the revision you recorded — `curl -fsSL -H "Authorization: Bearer <token>" -o architecture/<file> "<download_url>?pack_revision=<pack_revision>"` (a 409 means the pack was revised since: call `get_build_pack` again, then `what_changed` with `from_revision`; a 404 just after a release means the server instance that answered has not loaded it yet: wait a few seconds and download again, for up to a minute — a 404 that stays is a missing part: report it, never write the file from memory); else write the `content` of every page of `get_build_part` unchanged. Either way its `sha256` must equal the part's, or fetch it again. Those copies are read-only: the code changes, they do not. Never rename, add or drop an element to make the code easier; if a contract looks wrong, record it as a question for the architect and implement it as written.
2. **Names are the architecture's.** Call this component **Hub API** in code comments, logs, metrics and documentation, with `od-hub-api` beside it where an identifier is needed (repository, workload, artifact). Name every other component by its canonical name, as the parts write it.
3. **Never fill a gap.** Every open question below is something the architecture does not state. Ask the person you work with; if no answer comes, or nobody attends this build, leave a `TODO(architecture)` that quotes the question and the part, and continue with the rest — a question that blocks the build follows rule 9. Never invent a value, a limit, a timeout or a behaviour.
4. **Security is never a gap to work around.** Deny by default: only the callers under Security reach this component. An interface whose authentication is not documented fails closed — it rejects every call, and its tests prove it — until the architect states it; never ship it open with a TODO. The `security` part lists the threats to mitigate and the security decisions in force; a contract with no security scheme does not mean the operation is public.
5. **The cross-cutting standards and the governing ADRs apply to every line.** Errors, versioning, identity, idempotency, observability and resilience follow `cross-cutting-standards` and the `adr:` parts; where the service needs a choice they do not make, ask — with no answer, or nobody attending, a `TODO(architecture)` as in rule 3.
6. **Budgets are requirements.** Each phase this component takes part in has a latency budget; measure the component against its share.
7. **The stack comes from the team's service template**, not from this pack: the pack states the technology, not the project skeleton, libraries or CI.
8. **Record the version and the revision.** Every commit that implements this pack names architecture version 1.5.0 and the `pack_revision` in `.architecture.json`.
9. **A question that blocks the build is escalated, never kept in a local file.** A RUN team maintains this architecture. When an open question blocks the build — a contract with nothing to implement, an interface whose authentication is not documented, a decision still `Proposed` the code depends on — stop that part and call `preview_escalation(project: "order-desk", …)`: `request`, what the developer asked you to build, in their words — the task that led here, never their answer to a question of yours; the questions, one per item; the components by canonical name and the `I-nn` integrations concerned; the trigger each one touches from `get_architecture`'s `support.triggers` (an authentication or authorization gap is `security-iam`; a gap no other trigger names is `low-confidence`) with its evidence — the part and the line; route `tier-1`. Show the user the issue it returns; only after a yes follow `file_with.steps` with the developer's own access, then `record_escalation`. Unattended, nobody says yes: an escalation writes nothing, so follow `file_with.steps` with this build's own identity, then `record_escalation`, and list the issue in your pull request. If the host refuses the issue for a label the repository does not have, file it again without the labels it refused — the `[ESC-?]` title identifies it — and name the missing labels beside the issue's URL, to the user or, unattended, in your pull request. A change to the architecture you would make that crosses a trigger takes the same path. If the server files no escalations for this project, give the user — unattended, your pull request — the same questions, each with its part and line, for the architects.
10. **A Tier 0 write is requested, never made here.** Four writes on this architecture need no architect (`get_architecture`'s `support.tier0_writes`): a workaround recorded as a TDR, a TDR status move, a project ADR in `Proposed`, a doc fix that changes no fact. They belong in the architecture repository, never in this one. When you take a shortcut this pack does not state — a cache, a retry, a stub, a relaxed budget — or your code resolves a TDR, call `preview_tier0(project: "order-desk", kind, …)`: `request`, the developer's task in their words; the components by canonical name; the evidence — this repository, the commit, the file and line; the fields its kind needs (`support.tier0_kinds`; a status move only along `support.tdr_moves`). Show the user the issue it returns; only after a yes follow `file_with.steps`, then `record_tier0`. Unattended, pass `supervision: "unattended"`: the issue carries no `architecture-tier0` label — file it with this build's own identity (drop that label from `file_with.steps` if the server still lists it), call `record_tier0`, and list it in your pull request; a person applies the label, and that is the approval. Never apply `architecture-tier0` yourself. If the host refuses the issue for a label the repository does not have, file it again without the labels it refused — the `[T0-?]` title identifies it — and name the missing labels beside the issue's URL, to the user or, unattended, in your pull request: without `architecture-tier0` in the repository nobody can apply the request. Mark the code of a workaround with the issue's URL, and replace it with the TDR's id when `what_changed` brings it — never in a file that must not change once applied (a database migration): mark the code that keeps the workaround instead. Whatever crosses a trigger is rule 9, never Tier 0.

## Build plan

### 1. Read the Component Specification: responsibility, invariants, budgets, data ownership

- `spec` — Component Specification (`spec.md`)
- `asset:c4-descriptor.md` — c4-descriptor.md (`assets/c4-descriptor.md`)

### 2. Enforce the security obligations first: who may call, how each interface authenticates, the threats to mitigate

- `security` — Security Obligations (`security.md`)

### 3. Implement the API and message contracts exactly as written

- `asset:openapi.yaml` — openapi.yaml (`assets/openapi.yaml`)

### 4. Implement the data model

- `asset:ddl.sql` — ddl.sql (`assets/ddl.sql`)

### 5. Implement each interface: outbound calls, then inbound

- `interface:hub-api→ledger-store` — Interface Contract: Hub API → Ledger Store (`interfaces/hub-api→ledger-store.md`)
- `interface:web-app→hub-api` — Interface Contract: Web App → Hub API (`interfaces/web-app→hub-api.md`)

### 6. Apply the cross-cutting standards and the governing ADRs

- `cross-cutting-standards` — Cross-Cutting Standards (`cross-cutting-standards.md`)
- `adr:ADR-012` — ADR-012: Service mesh (`adrs/ADR-012-service-mesh.md`)
- `adr:ADR-101` — ADR-101: Idempotency keys on every write (`adrs/ADR-101-idempotency-keys.md`)

### 7. Meet the flows the component takes part in and their latency budgets

- `runtime-view` — System Runtime View (`runtime-view.md`)
- the flows and budgets below

### 8. Prove the acceptance criteria of the Component Specification

- `spec` — § Acceptance of the Component Specification: each criterion becomes an automated test

## Security

Who may call this component and how each interface authenticates (the `security` part; its threats and tests follow there):

| Integration | Caller | Protocol | Authentication | Source |
|---|---|---|---|---|
| I-01 | Web App | REST | mTLS through the mesh | STRIDE TB-01 |

Any other caller is rejected. Ledger Store calls back over I-02.

## Interfaces

| Direction | Interface | Part |
|---|---|---|
| outbound | Hub API → Ledger Store | `interface:hub-api→ledger-store` |
| inbound | Web App → Hub API | `interface:web-app→hub-api` |

## Flows and latency budgets

| Flow | Use cases | Flow target | Phase | Hops | Phase budget |
|---|---|---|---|---|---|
| 1. Place order | UC-01 | 300 ms | 1. Accept order | Web App → Hub API | 120 ms |
| 1. Place order | UC-01 | 300 ms | 2. Book order | Hub API → Ledger Store | 90 ms |

## Open questions

None.

## Done when

- [ ] every contract asset is implemented exactly, and the contract the service publishes matches it
- [ ] every asset is committed under `architecture/`, its `sha256` equal to the part's
- [ ] every interface handles the failure semantics its Interface Contract states
- [ ] every test under Security Tests of the `security` part passes: a call without credentials and a call from an unlisted caller are rejected, and an interface without documented authentication rejects every call
- [ ] the acceptance criteria of the Component Specification pass as automated tests
- [ ] every latency budget above is measured
- [ ] every open question is answered or left as a `TODO(architecture)` that quotes it
- [ ] `.architecture.json` names version 1.5.0 and the `pack_revision` you built from

#### Debt x <!-- LABEL: Technical Debt -->
