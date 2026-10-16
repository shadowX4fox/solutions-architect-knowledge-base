# Build Hub API

You are building **Hub API** (`od-hub-api`) — API Service, Spring Boot, Kafka — as specified by the architecture **Order Desk**, version **1.4.0**. Everything you need is in this build pack. Fetch each part with `get_build_part(project: "order-desk", component: "hub-api", part: "<part id>")`; ask the architecture questions with `search_architecture` while you work.

## Bind the repository first

Write `.architecture.json` at the root of the repository, or check the one there names this component and this version. Call `get_build_pack(project: "order-desk", component: "hub-api")` and replace the `pack_revision` placeholder with the `pack_revision` it returns — the revision of this pack you are building from:

```json
{
  "server": "http://localhost:8080/mcp",
  "project": "order-desk",
  "component": "hub-api",
  "architecture_version": "1.4.0",
  "pack_revision": "<pack_revision from get_build_pack>"
}
```

When the architecture releases a new version, or this pack is revised within its version (`get_build_pack` returns another `pack_revision`), call `what_changed(project: "order-desk", component: "hub-api", from: "<the version in this file>", from_revision: "<the pack_revision in this file>", to: "<the new version>")`, update the service, then this file.

## Rules

1. **Contracts are implemented exactly.** Every asset under `asset:` is the contract: paths, operations, messages, fields, types, tables, keys and their names as written. Generate code from it where a generator exists. Never rename, add or drop an element to make the code easier; if a contract looks wrong, record it as a question for the architect and implement it as written.
2. **Names are the architecture's.** Call this component **Hub API** in code comments, logs, metrics and documentation, with `od-hub-api` beside it where an identifier is needed (repository, workload, artifact). Name every other component by its canonical name, as the parts write it.
3. **Never fill a gap.** Every open question below is something the architecture does not state. Ask the person you work with; if no answer comes, leave a `TODO(architecture)` that quotes the question and the part, and continue with the rest. Never invent a value, a limit, a timeout or a behaviour.
4. **Security is never a gap to work around.** Deny by default: only the callers under Security reach this component. An interface whose authentication is not documented fails closed — it rejects every call, and its tests prove it — until the architect states it; never ship it open with a TODO. The `security` part lists the threats to mitigate and the security decisions in force; a contract with no security scheme does not mean the operation is public.
5. **The cross-cutting standards and the governing ADRs apply to every line.** Errors, versioning, identity, idempotency, observability and resilience follow `cross-cutting-standards` and the `adr:` parts; where the service needs a choice they do not make, ask.
6. **Budgets are requirements.** Each phase this component takes part in has a latency budget; measure the component against its share.
7. **The stack comes from the team's service template**, not from this pack: the pack states the technology, not the project skeleton, libraries or CI.
8. **Record the version and the revision.** Every commit that implements this pack names architecture version 1.4.0 and the `pack_revision` in `.architecture.json`.

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

Any other caller is rejected.

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
- [ ] every interface handles the failure semantics its Interface Contract states
- [ ] every test under Security Tests of the `security` part passes: a call without credentials and a call from an unlisted caller are rejected, and an interface without documented authentication rejects every call
- [ ] the acceptance criteria of the Component Specification pass as automated tests
- [ ] every latency budget above is measured
- [ ] every open question is answered or left as a `TODO(architecture)` that quotes it
- [ ] `.architecture.json` names version 1.4.0 and the `pack_revision` you built from
