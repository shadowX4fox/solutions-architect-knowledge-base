# Hub API — Security Obligations

<!-- managed by sa-skills:architecture-dev-handoff — rendered by security-obligations.js from the architecture docs and the security analyses; fix a value there and re-run, never here -->

## Inbound — who may call <!-- SECTION: Inbound -->

| Integration | Caller | Protocol | Authentication | Source |
|---|---|---|---|---|
| I-01 | Web App | REST | mTLS through the mesh | STRIDE TB-01 |

Any other caller is rejected.

## Decisions in force <!-- SECTION: Decisions in Force -->

- [ADR-012](adrs/ADR-012-service-mesh.md) — Service mesh
