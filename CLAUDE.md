# CLAUDE.md

The architecture MCP server (Spring Boot 4.1, Spring AI 2.0, Qdrant Java client, Java 21). README.md describes the tools, the publishing contract and the settings.

## Commands

```bash
./mvnw test                      # all tests; in-memory index and a stand-in embedding model, no services needed
./mvnw package -DskipTests       # target/architecture-index-server-*.jar
SA_SKILLS_DIR=<plugin checkout> bun scripts/make-fixtures.ts src/test/resources/bundles/order-desk   # regenerate fixtures
```

## Rules

- **The plugin owns the bundle schema** (`sa-index/1`, `plugins/sa-delivery/skills/architecture-index-export/` in solutions-architect-skills). This server reads it and never redefines it. A schema change starts in the plugin, then the fixtures are regenerated here.
- **The plugin owns the publishing contract.** `ci/publish-index.sh` names the status codes; `BundleController` and `BundleStore` implement them.
- **Ports stay ports.** `BundleChecker` is the plugin's `checkBundle`, and `BundleDiff` is its `diffBundles` and `renderChanges`, field for field. `BundleTest.theJavaDiffIsThePluginsDiff` holds the diff to the plugin's own `changes.json`. When the plugin's diff changes, port the change and regenerate the fixtures.
- **Lists and relations come from the ledger, never from search.** A component is matched by exact slug, canonical name or internal name, ignoring case, and never partially. A docs/05 row's From/To is reported as written; direction is not inferred.
- **A build never goes through embeddings.** Pack parts are served byte for byte. A missing pack is an error naming the reason and the versions that have one; the server never falls back to another version.
- **Released versions are immutable** in what search serves: a re-publish carries the same chunks, the same ledger and every pack held; it may add packs and replace them, never drop one.
- **Fixtures are synthetic.** They are the plugin's Order Desk test project. Never commit a bundle exported from a real architecture.
- Jackson 3 (`tools.jackson`) is Boot 4's. Bundle JSON is read into plain maps through `bundle/J.java`, so a field a newer plugin adds never breaks the server.
