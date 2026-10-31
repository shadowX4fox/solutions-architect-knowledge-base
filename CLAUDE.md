# CLAUDE.md

The architecture MCP server (Spring Boot 4.1, Spring AI 2.0, Qdrant Java client, Java 21). README.md describes the tools, the publishing contract and the settings.

## Commands

```bash
./mvnw test                      # all tests; in-memory index and a stand-in embedding model, no services needed
QDRANT_TEST_HOST=localhost QDRANT_TEST_PORT=6334 ./mvnw test -Dtest=QdrantTest   # the indexer against a real Qdrant
./mvnw package -DskipTests       # target/architecture-index-server-*.jar
SA_SKILLS_DIR=<plugin checkout> bun scripts/make-fixtures.ts src/test/resources/bundles/order-desk   # regenerate fixtures
```

## Rules

- **The plugin owns the bundle schema** (`sa-index/1`, `plugins/sa-delivery/skills/architecture-index-export/` in solutions-architect-skills). This server reads it and never redefines it. A schema change starts in the plugin, then the fixtures are regenerated here.
- **The plugin owns the publishing contract.** `ci/publish-index.sh` names the status codes; `BundleController` and `BundleStore` implement them.
- **Ports stay ports.** `BundleChecker` is the plugin's `checkBundle`, and `BundleDiff` is its `diffBundles` and `renderChanges`, field for field. `BundleTest.theJavaDiffIsThePluginsDiff` holds the diff to the plugin's own `changes.json`. When the plugin's diff changes, port the change and regenerate the fixtures.
- **Lists and relations come from the ledger, never from search.** A component is matched by exact slug, canonical name or internal name, ignoring case, and never partially. A docs/05 row's From/To is reported as written; direction is not inferred.
- **A build never goes through embeddings.** Pack parts are served byte for byte. A missing pack is an error naming the reason and the versions that have one; the server never falls back to another version.
- **One writer, any number of readers.** A bundle directory never changes once placed: a publish writes a new one, then `HEAD.json`, then `.generation`; a replaced one is deleted after the retention. Readers (`archindex.role=reader`) never write to the data directory or the index.
- **Released versions are immutable** in what search serves: a re-publish carries the same chunks, the same ledger and every pack held; it may add packs and replace them, never drop one. The ledger may be extended (keys a newer plugin derives, every value held unchanged), never changed.
- **The escalation issue is the plugin's.** `Escalate` is `escalate.js`'s `validate`, `render` and `issueBody`, with its text rules (ECMAScript `trim()` and `\s`). `EscalateTest` holds it to the plugin's own output in `escalation/issues.json`. So is the Tier 0 request: `Tier0` is `tier0.js`'s `validate`, `issueTitle`, `labelsOf` and `issueBody`, with JavaScript's values (`filled`, `===`, `Number()`, `JSON.stringify`), held by `Tier0Test` to `tier0/issues.json`. The server never files: it renders, names the host, and logs.
- **Fixtures are synthetic.** They are the plugin's Order Desk test project. Never commit a bundle exported from a real architecture.
- Jackson 3 (`tools.jackson`) is Boot 4's. Bundle JSON is read into plain maps through `bundle/J.java`, so a field a newer plugin adds never breaks the server.
- **The MCP server is built by `AliaServer`**, not Spring AI's `McpServerStatelessAutoConfiguration` (excluded), so `serverInfo` carries Alia's title and escudo. On a Spring AI upgrade, compare it with that autoconfiguration's `mcpStatelessSyncServer`. The escudo (`src/main/resources/alia/escudo.svg`) is the plugin's `brand/alia/blazon/escudo.svg`, drawn as is: a change starts in the plugin.
