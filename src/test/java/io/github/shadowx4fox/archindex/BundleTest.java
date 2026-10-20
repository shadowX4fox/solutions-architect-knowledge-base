package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleChecker;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.BundleStore.Outcome;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.bundle.Semver;
import io.github.shadowx4fox.archindex.changes.BundleDiff;
import io.github.shadowx4fox.archindex.index.Embedder;
import io.github.shadowx4fox.archindex.index.FileVectorIndex;
import io.github.shadowx4fox.archindex.index.Indexer;
import io.github.shadowx4fox.archindex.index.SparseEncoder;
import io.github.shadowx4fox.archindex.index.VectorIndex;

class BundleTest {

	@TempDir
	Path tmp;

	@Test
	void everyFixtureBundlePassesTheCheckAsThePluginWroteIt() {
		for (String b : List.of("1.3.0-search", "1.3.0", "1.4.0", "1.4.0-revised", "draft")) {
			BundleChecker.Result r = BundleChecker.check(Fixtures.bundle(b));
			assertThat(r.findings()).as(b).isEmpty();
			assertThat(r.chunks()).isEqualTo(23);
		}
	}

	@Test
	void aPackWithoutItsSecurityPartFailsTheCheck() throws Exception {
		Path b = Fixtures.copy(Fixtures.bundle("1.3.0"), tmp.resolve("b"));
		Map<String, Object> pack = J.obj(Files.readAllBytes(b.resolve("buildpacks/hub-api/pack.json")));
		pack.put("parts", J.maps(pack, "parts").stream().filter((x) -> !"security".equals(J.str(x, "id"))).toList());
		pack.put("plan", J.maps(pack, "plan").stream().map((st) -> {
			Map<String, Object> m = new java.util.LinkedHashMap<>(st);
			m.put("parts", J.strings(st, "parts").stream().filter((id) -> !"security".equals(id)).toList());
			return m;
		}).toList());
		Fixtures.rewrite(b, "buildpacks/hub-api/pack.json", J.write(pack));
		assertThat(BundleChecker.check(b).findings()).extracting(BundleChecker.Finding::text).containsExactly("no security part");
	}

	@Test
	void theCheckFindsAnEditedFileAnEditedChunkAndAnEditedPart() throws Exception {
		Path b = Fixtures.copy(Fixtures.bundle("1.3.0"), tmp.resolve("b"));
		Files.writeString(b.resolve("buildpacks/hub-api/spec.md"), "edited\n");
		List<String> lines = Files.readAllLines(b.resolve("chunks.jsonl"));
		Map<String, Object> c = J.obj(lines.get(0));
		c.put("text", c.get("text") + " edited");
		lines.set(0, J.write(c));
		Files.write(b.resolve("chunks.jsonl"), lines);
		Files.writeString(b.resolve("stray.txt"), "x");
		BundleChecker.Result r = BundleChecker.check(b);
		assertThat(r.pass()).isFalse();
		assertThat(r.findings()).extracting(BundleChecker.Finding::text)
			.contains("sha256 differs from the manifest", "content_hash is not the text's", "part spec: sha256 differs",
					"on disk, not in the manifest");
	}

	@Test
	void chunkIdsHoldAcrossVersionsAndOnlyEditedChunksChangeTheirHash() {
		Bundle a = Bundle.load(Fixtures.bundle("1.3.0"));
		Bundle b = Bundle.load(Fixtures.bundle("1.4.0"));
		assertThat(b.chunks()).extracting((c) -> c.id()).containsExactlyInAnyOrderElementsOf(a.chunks().stream().map((c) -> c.id()).toList());
		assertThat(a.chunkSignature()).isNotEqualTo(b.chunkSignature());
		assertThat(Bundle.load(Fixtures.bundle("1.3.0-search")).chunkSignature()).isEqualTo(a.chunkSignature());
	}

	@Test
	void theJavaDiffIsThePluginsDiff() {
		Bundle o = Bundle.load(Fixtures.bundle("1.3.0"));
		Bundle n = Bundle.load(Fixtures.bundle("1.4.0"));
		Map<String, Object> shipped = n.changes().orElseThrow();
		Map<String, Object> ours = BundleDiff.diff(o, n, null, false);
		// the same JSON once both are read back; a null field is compared as absent (JSON.stringify drops undefined)
		assertThat(dropNulls(J.obj(J.write(ours)))).isEqualTo(dropNulls(shipped));
		String md = BundleDiff.render(BundleDiff.diff(o, n, "hub-api", false));
		assertThat(md).contains("## Hub API (`hub-api`) — action required", "- contracts: changed openapi.yaml",
				"- flow 1. Place order: changed — phase 2 latency_budget 80 ms → 90 ms", "  - I-01 pattern: sync → async");
		assertThatThrownBy(() -> BundleDiff.diff(o, n, "nope", false)).hasMessageContaining("container of neither version");
	}

	@Test
	void aPartIsComparedByItsSubstance() throws Exception {
		// the plugin's diffBundles and renderChanges of the pair (scripts/make-fixtures.ts): a part that moved only its version,
		// its link targets, its line ends or the specification's debt table is restamped, never an action; the rest is compared
		// with JavaScript's regular expressions
		Bundle o = Bundle.load(Fixtures.bundle("1.4.0-stamped"));
		Bundle n = Bundle.load(Fixtures.bundle("1.5.0-restamped"));
		Map<String, Object> ours = BundleDiff.diff(o, n, null, false);
		assertThat(dropNulls(J.obj(J.write(ours)))).isEqualTo(dropNulls(n.changes().orElseThrow()));
		assertThat(BundleDiff.render(ours)).isEqualTo(Files.readString(Fixtures.bundle("1.5.0-restamped").resolve("changes.md")));
		Map<String, Object> hub = J.maps(ours, "components").get(0);
		assertThat(J.strings(hub, "restamped")).containsExactly("c4-descriptor.md", "Interface Contract: Web App → Hub API",
				"ADR-101: Idempotency keys on every write", "Component Specification");
		assertThat(J.strings(hub, "documents")).containsExactly("Security Obligations", "System Runtime View", "Build instructions");
		assertThat(J.strings(J.map(hub, "adrs"), "changed")).containsExactly("ADR-012");
		assertThat(J.maps(J.map(hub, "open_questions"), "new")).extracting((q) -> q.get("text")).containsExactly("Which caller signs?");

		assertThat(BundleDiff.substance("x\r\ny\rz", null)).isEqualTo("x\ny\nz");
		assertThat(BundleDiff.substance("[a](b c) [d](e)", "1.0")).isEqualTo("[a](b c) [d](·)");
		assertThat(BundleDiff.substance("v1.0 é1.0 1.0x _1.0 1.0.1 (1.0)", "1.0")).isEqualTo("· é· 1.0x _1.0 ·.1 (·)");
		assertThat(BundleDiff.substance("## A\n### Debt <!-- LABEL: Technical Debt -->\n| x |\n---\nrest\n### B <!-- LABEL: Technical Debt -->\ny\n", null))
			.isEqualTo("## A\n---\nrest\n### B <!-- LABEL: Technical Debt -->\ny\n");
	}

	/** 1.3.0 with hub-api's descriptor naming {@code names} and the ledger holding {@code tdrs}; null names: no `tdrs` at all. */
	Bundle withDebt(String dir, List<String> names, List<Map<String, Object>> tdrs) throws Exception {
		Path b = Fixtures.copy(Fixtures.bundle("1.3.0"), tmp.resolve(dir));
		Map<String, Object> ledger = J.obj(Files.readAllBytes(b.resolve("ledger.json")));
		J.maps(ledger, "components").forEach((c) -> c.remove("tdrs"));
		if (names != null) {
			J.maps(ledger, "components").stream().filter((c) -> "hub-api".equals(J.str(c, "slug"))).findFirst().orElseThrow().put("tdrs", names);
		}
		ledger.put("tdrs", tdrs);
		Files.writeString(b.resolve("ledger.json"), J.write(ledger));
		return Bundle.load(b);
	}

	static Map<String, Object> tdr(String id, String title, String status) {
		return new java.util.LinkedHashMap<>(Map.of("id", id, "title", title, "status", status));
	}

	@Test
	void technicalDebtIsListedNeverAnAction() throws Exception {
		// the plugin's test (export-index.test.ts, "technical debt: …"): a TDR the descriptor starts naming, a status move, one it stops naming
		Bundle a = withDebt("a", List.of("TDR-001"), List.of(tdr("TDR-001", "Batch settlement", "Open")));
		Bundle b = withDebt("b", List.of("TDR-001", "TDR-002"),
				List.of(tdr("TDR-001", "Batch settlement", "In Progress"), tdr("TDR-002", "Keys never purged", "Open")));
		Map<String, Object> hub = J.maps(BundleDiff.diff(a, b, null, false), "components").get(0);
		assertThat(J.str(hub, "component")).isEqualTo("hub-api");
		assertThat(J.obj(J.write(hub.get("technical_debt")))).isEqualTo(J.obj("""
				{"added": [{"id": "TDR-002", "title": "Keys never purged", "status": "Open"}], "removed": [],
				 "status": [{"id": "TDR-001", "title": "Batch settlement", "from": "Open", "to": "In Progress"}]}"""));
		assertThat(hub).containsEntry("changed", true).containsEntry("action_required", false);
		assertThat(BundleDiff.render(BundleDiff.diff(a, b, null, false)))
			.contains("- technical debt: added TDR-002 — Keys never purged (Open) · TDR-001 Open → In Progress");
		assertThat(BundleDiff.render(BundleDiff.diff(b, a, null, false))).contains("- technical debt: TDR-001 In Progress → Open · no longer names TDR-002");
		// a bundle written before the ledger carried a component's debt reads as none
		Bundle old = withDebt("old", null, List.of());
		assertThat(J.maps(J.map(J.maps(BundleDiff.diff(old, a, null, false), "components").get(0), "technical_debt"), "added"))
			.extracting((t) -> t.get("id")).containsExactly("TDR-001");
		// a TDR the ledger does not hold: its id, nothing made up
		Bundle unknown = withDebt("unknown", List.of("TDR-009"), List.of());
		assertThat(BundleDiff.render(BundleDiff.diff(old, unknown, null, false))).contains("- technical debt: added TDR-009\n");
	}

	@Test
	void theJavaDiffIsThePluginsDiffForTheSameVersionTwice() {
		Bundle o = Bundle.load(Fixtures.bundle("1.4.0"));
		Bundle n = Bundle.load(Fixtures.bundle("1.4.0-revised"));
		Map<String, Object> ours = BundleDiff.diff(o, n, null, false);
		assertThat(dropNulls(J.obj(J.write(ours)))).isEqualTo(dropNulls(n.changes().orElseThrow()));
		Map<String, Object> hub = J.maps(ours, "components").get(0);
		assertThat(ours.get("same_version")).isEqualTo(true);
		assertThat(hub).containsEntry("pack_revised", true).containsEntry("action_required", true);
		assertThat(J.strings(hub, "documents")).containsExactly("Security Obligations", "Build instructions");
		assertThat(J.map(hub, "pack_revision")).containsEntry("from", o.packRevision("hub-api").orElseThrow())
			.containsEntry("to", n.packRevision("hub-api").orElseThrow());
		String md = BundleDiff.render(ours);
		assertThat(md).contains("# What changed — order-desk 1.4.0, build packs revised",
				"- build pack replaced: revision " + o.packRevision("hub-api").orElseThrow().substring(0, 12) + " → "
						+ n.packRevision("hub-api").orElseThrow().substring(0, 12),
				"- documents: Security Obligations, Build instructions");
		// nothing moved: the same bundle twice is no change, and its pack is not revised
		assertThat(J.maps(BundleDiff.diff(o, o, null, false), "components")).isEmpty();
		assertThat(BundleDiff.diff(o, o, "hub-api", false)).extracting((d) -> J.maps(d, "components").get(0))
			.satisfies((c) -> assertThat(c).containsEntry("pack_revised", false).containsEntry("changed", false));
	}

	@SuppressWarnings("unchecked")
	static <T> T dropNulls(T v) {
		if (v instanceof Map<?, ?> m) {
			java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
			m.forEach((k, x) -> {
				if (x != null) {
					out.put((String) k, dropNulls(x));
				}
			});
			return (T) out;
		}
		if (v instanceof List<?> l) {
			return (T) l.stream().map(BundleTest::dropNulls).toList();
		}
		return v;
	}

	@Test
	void semverOrdersVersionsNumerically() {
		assertThat(List.of("1.10.0", "1.2.0", "1.2.0-rc1", "0.9.9").stream().sorted(Semver.ORDER).toList())
			.containsExactly("0.9.9", "1.2.0-rc1", "1.2.0", "1.10.0");
	}

	@Test
	void theStoreFollowsThePublishContract() throws Exception {
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		// phase 1 at the tag: chunks and ledger, no packs → 201
		var p = publish(s, "order-desk", "1.3.0", "released", Fixtures.bundle("1.3.0-search"));
		assertThat(p.outcome()).isEqualTo(Outcome.STORED);
		assertThat(p.chunksChanged()).isTrue();
		// phase 2, the kit committed: the same chunks with the Hub API pack → 200, packs added
		p = publish(s, "order-desk", "1.3.0", "released", Fixtures.bundle("1.3.0"));
		assertThat(p.outcome()).isEqualTo(Outcome.PACKS_UPDATED);
		assertThat(p.added()).containsExactly("hub-api");
		assertThat(p.replaced()).isEmpty();
		assertThat(p.chunksChanged()).isFalse();
		assertThat(s.resolve("order-desk", "1.3.0").orElseThrow().packs()).containsKey("hub-api");
		// the tag job re-run: no packs in it, the held packs stay
		assertThat(publish(s, "order-desk", "1.3.0", "released", Fixtures.bundle("1.3.0-search")).outcome()).isEqualTo(Outcome.UNCHANGED);
		assertThat(s.resolve("order-desk", "1.3.0").orElseThrow().packs()).containsKey("hub-api");
		// a draft lands in its own slot; latest stays the release
		assertThat(publish(s, "order-desk", "1.4.0", "draft", Fixtures.bundle("draft")).outcome()).isEqualTo(Outcome.STORED);
		assertThat(s.resolve("order-desk", null).orElseThrow().version()).isEqualTo("1.3.0");
		assertThat(s.resolve("order-desk", "draft")).isPresent();
		// the release of that version supersedes the draft
		assertThat(publish(s, "order-desk", "1.4.0", "released", Fixtures.bundle("1.4.0")).outcome()).isEqualTo(Outcome.STORED);
		assertThat(s.resolve("order-desk", "draft")).isEmpty();
		assertThat(s.resolve("order-desk", "latest").orElseThrow().version()).isEqualTo("1.4.0");
		assertThat(s.before("order-desk", "1.4.0").orElseThrow().version()).isEqualTo("1.3.0");
		assertThat(s.before("order-desk", "1.3.0")).isEmpty();
		// a released version never changes what search serves: other chunks under 1.4.0 → 409
		Path forged = Fixtures.copy(Fixtures.bundle("draft"), tmp.resolve("forged"));
		Map<String, Object> m = J.obj(Files.readAllBytes(forged.resolve("manifest.json")));
		m.put("channel", "released");
		Files.writeString(forged.resolve("manifest.json"), J.write(m));
		p = publish(s, "order-desk", "1.4.0", "released", forged);
		assertThat(p.outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(p.conflict()).isEqualTo(BundleStore.Conflict.CHUNKS_DIFFER);
		// the URL must name the bundle; a failing bundle is refused with its findings
		assertThatThrownBy(() -> publish(s, "order-desk", "1.5.0", "released", Fixtures.bundle("1.4.0")))
			.isInstanceOf(BundleStore.Rejected.class).hasMessageContaining("the bundle is version 1.4.0");
		Path broken = Fixtures.copy(Fixtures.bundle("1.4.0"), tmp.resolve("broken"));
		Files.writeString(broken.resolve("buildpacks/hub-api/assets/openapi.yaml"), "openapi: 3.1.0\n");
		assertThatThrownBy(() -> publish(s, "order-desk", "1.4.0", "released", broken)).isInstanceOfSatisfying(
				BundleStore.Rejected.class, (e) -> assertThat(e.status()).isEqualTo(422));
		// what was held survives a restart
		BundleStore again = new BundleStore(tmp.resolve("data"), 50_000_000);
		assertThat(again.find("order-desk").orElseThrow().released().keySet()).containsExactly("1.3.0", "1.4.0");
		assertThat(again.resolve("order-desk", "1.3.0").orElseThrow().packs()).containsKey("hub-api");
	}

	@Test
	void aSecondPublishOfAReleasedVersionMayAddAndReplacePacksButNeverDropOneOrMoveTheLedger() throws Exception {
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		publish(s, "order-desk", "1.4.0", "released", Fixtures.bundle("1.4.0"));
		String first = s.resolve("order-desk", "1.4.0").orElseThrow().packRevision("hub-api").orElseThrow();
		// the same bundle again (a re-run of the kit job): nothing moves
		var p = publish(s, "order-desk", "1.4.0", "released", Fixtures.bundle("1.4.0"));
		assertThat(p.outcome()).isEqualTo(Outcome.UNCHANGED);
		assertThat(p.unchanged()).containsExactly("hub-api");
		// the kit regenerated: Hub API's security obligations moved → its pack is replaced, search is untouched
		p = publish(s, "order-desk", "1.4.0", "released", Fixtures.bundle("1.4.0-revised"));
		assertThat(p.outcome()).isEqualTo(Outcome.PACKS_UPDATED);
		assertThat(p.replaced()).containsExactly("hub-api");
		assertThat(p.added()).isEmpty();
		assertThat(p.chunksChanged()).isFalse();
		assertThat(s.resolve("order-desk", "1.4.0").orElseThrow().packRevision("hub-api").orElseThrow()).isNotEqualTo(first);
		// another ledger under the same chunks (a plugin bump between the two publishes) → 409 ledger_differs
		Path ledger = Fixtures.copy(Fixtures.bundle("1.4.0-revised"), tmp.resolve("ledger"));
		Map<String, Object> l = J.obj(Files.readAllBytes(ledger.resolve("ledger.json")));
		l.put("plugin_field", "new");
		Fixtures.rewrite(ledger, "ledger.json", J.write(l));
		p = publish(s, "order-desk", "1.4.0", "released", ledger);
		assertThat(p.outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(p.conflict()).isEqualTo(BundleStore.Conflict.LEDGER_DIFFERS);
		assertThat(s.resolve("order-desk", "1.4.0").orElseThrow().ledger()).doesNotContainKey("plugin_field");
	}

	@Test
	void aSecondPublishThatLeavesOutAPackHeldIsRefusedAndAnOlderLedgerClockIsNotAChange() throws Exception {
		// held from an older export: a second pack (web-app) and a ledger that still carried the run's clock
		Path held = Fixtures.copy(Fixtures.bundle("1.4.0"), tmp.resolve("data/bundles/order-desk/released/1.4.0"));
		Fixtures.copy(held.resolve("buildpacks/hub-api"), held.resolve("buildpacks/web-app"));
		Map<String, Object> pack = J.obj(Files.readAllBytes(held.resolve("buildpacks/web-app/pack.json")));
		pack.put("component", "web-app");
		Files.writeString(held.resolve("buildpacks/web-app/pack.json"), J.write(pack));
		Map<String, Object> m = J.obj(Files.readAllBytes(held.resolve("manifest.json")));
		List<Map<String, Object>> packs = new java.util.ArrayList<>(J.maps(m, "buildpacks"));
		packs.add(Map.of("component", "web-app", "path", "buildpacks/web-app/pack.json"));
		m.put("buildpacks", packs);
		Files.writeString(held.resolve("manifest.json"), J.write(m));
		Map<String, Object> l = J.obj(Files.readAllBytes(held.resolve("ledger.json")));
		l.put("generated_at", "2026-10-01T10:00:00Z");
		Files.writeString(held.resolve("ledger.json"), J.write(l));
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		assertThat(s.resolve("order-desk", "1.4.0").orElseThrow().packs()).containsOnlyKeys("hub-api", "web-app");
		var p = publish(s, "order-desk", "1.4.0", "released", Fixtures.bundle("1.4.0-revised"));
		assertThat(p.outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(p.conflict()).isEqualTo(BundleStore.Conflict.PACKS_MISSING);
		assertThat(p.conflictPacks()).containsExactly("web-app");
		assertThat(s.resolve("order-desk", "1.4.0").orElseThrow().packs()).containsOnlyKeys("hub-api", "web-app");
		// the tag job re-run, no packs: kept as held
		Path search = Fixtures.copy(Fixtures.bundle("1.4.0-revised"), tmp.resolve("search"));
		Map<String, Object> sm = J.obj(Files.readAllBytes(search.resolve("manifest.json")));
		sm.put("buildpacks", List.of());
		sm.put("files", J.maps(sm, "files").stream().filter((f) -> !J.str(f, "path").startsWith("buildpacks/")).toList());
		Map<String, Object> counts = new java.util.LinkedHashMap<>(J.map(sm, "counts"));
		counts.put("buildpacks", 0);
		sm.put("counts", counts);
		Files.writeString(search.resolve("manifest.json"), J.write(sm));
		org.springframework.util.FileSystemUtils.deleteRecursively(search.resolve("buildpacks"));
		assertThat(BundleChecker.check(search).findings()).isEmpty();
		p = publish(s, "order-desk", "1.4.0", "released", search);
		assertThat(p.outcome()).isEqualTo(Outcome.UNCHANGED);
		assertThat(p.unchanged()).containsExactly("hub-api", "web-app");
	}

	@Test
	void aBundleThatTriesToLeaveItsFolderIsRefused() throws Exception {
		Path evil = tmp.resolve("evil.tgz");
		try (var out = Files.newOutputStream(evil);
				var gz = new java.util.zip.GZIPOutputStream(out);
				var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gz)) {
			var e = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("../escape.txt");
			e.setSize(1);
			tar.putArchiveEntry(e);
			tar.write('x');
			tar.closeArchiveEntry();
		}
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		assertThatThrownBy(() -> s.publish("order-desk", "1.0.0", "released", Files.newInputStream(evil)))
			.isInstanceOfSatisfying(BundleStore.Rejected.class, (e) -> assertThat(e.getMessage()).contains("leaves the bundle"));
		assertThat(tmp.resolve("data/.incoming/escape.txt")).doesNotExist();
	}

	@Test
	void theFileIndexOutlivesARestartAndRefusesAnotherModelsSize() throws Exception {
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		publish(s, "order-desk", "1.3.0", "released", Fixtures.bundle("1.3.0"));
		publish(s, "order-desk", "1.4.0", "draft", Fixtures.bundle("draft"));
		Path file = tmp.resolve("data/index/vectors-1024.bin");
		var first = new FileVectorIndex(file, 1024);
		Indexer.SlotState released = reconcile(s, first).state("order-desk", "1.3.0");
		assertThat(released.status()).isEqualTo(Indexer.Status.READY);
		assertThat(released.embedded()).isEqualTo(23);
		assertThat(file).exists();
		// a restart reads the file: the release is taken as held, the draft reuses every vector it shares with it
		var again = new FileVectorIndex(file, 1024);
		Indexer restarted = reconcile(s, again);
		assertThat(restarted.state("order-desk", "1.3.0").embedded()).isZero();
		assertThat(restarted.state("order-desk", "draft").status()).isEqualTo(Indexer.Status.READY);
		assertThat(again.count("order-desk", "1.3.0")).isEqualTo(first.count("order-desk", "1.3.0"));
		var q = new VectorIndex.Query(Fixtures.HashingEmbeddingModel.vector("ledger store"),
				new SparseEncoder().query("ledger store"), List.of(new VectorIndex.Scope("order-desk", "1.3.0")), null,
				null, 5, 50, 0.3);
		assertThat(again.search(q)).isEqualTo(first.search(q));
		assertThatThrownBy(() -> new FileVectorIndex(file, 768).ensure()).hasMessageContaining("1024-dimension");
	}

	@Test
	void aReaderFollowsTheWriterAndAReplacedDirectoryStaysForItsRetention() throws Exception {
		Path data = tmp.resolve("data");
		// a data directory from before HEAD.json: the reader reads it as it is, the writer takes it over
		Fixtures.copy(Fixtures.bundle("1.3.0"), data.resolve("bundles/order-desk/released/1.3.0"));
		Path leftover = Files.createDirectories(data.resolve("bundles/order-desk/released/9.9.9~dead"));
		Files.setLastModifiedTime(leftover, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));
		BundleStore reader = new BundleStore(data, 50_000_000, true, java.time.Duration.ofMinutes(10));
		assertThat(reader.resolve("order-desk", "1.3.0")).isPresent();
		BundleStore writer = new BundleStore(data, 50_000_000, false, java.time.Duration.ofMinutes(10));
		Map<String, Object> head = J.obj(Files.readAllBytes(data.resolve("bundles/order-desk/HEAD.json")));
		assertThat(J.map(head, "released")).containsEntry("1.3.0", "released/1.3.0");
		assertThat(leftover).doesNotExist();   // a crash's leftover, older than the retention
		// a publish reaches the reader on its next refresh; an unchanged version is the same object
		var before = reader.resolve("order-desk", "1.3.0").orElseThrow();
		reader.refresh();
		assertThat(publish(writer, "order-desk", "1.4.0", "released", Fixtures.bundle("1.4.0")).outcome()).isEqualTo(Outcome.STORED);
		assertThat(reader.resolve("order-desk", "1.4.0")).isEmpty();
		assertThat(reader.refresh()).containsExactly("order-desk");
		assertThat(reader.resolve("order-desk", "1.4.0")).isPresent();
		assertThat(reader.resolve("order-desk", "1.3.0").orElseThrow()).isSameAs(before);
		assertThat(reader.refresh()).isEmpty();
		// a pack replaced: a new directory; the one replaced stays for the reader still serving it
		Path old = reader.resolve("order-desk", "1.4.0").orElseThrow().dir();
		assertThat(publish(writer, "order-desk", "1.4.0", "released", Fixtures.bundle("1.4.0-revised")).outcome()).isEqualTo(Outcome.PACKS_UPDATED);
		assertThat(old).isDirectory();
		assertThat(reader.resolve("order-desk", "1.4.0").orElseThrow().dir()).isEqualTo(old);   // not reloaded yet: still whole
		assertThat(reader.refresh()).containsExactly("order-desk");
		assertThat(reader.resolve("order-desk", "1.4.0").orElseThrow().dir()).isNotEqualTo(old);
		assertThat(reader.find("order-desk").orElseThrow().revisions("1.4.0")).hasSize(1);
		// a writer with no retention deletes what was replaced at its start
		new BundleStore(data, 50_000_000, false, java.time.Duration.ZERO);
		assertThat(old).doesNotExist();
		assertThat(J.maps(J.obj(Files.readAllBytes(data.resolve("bundles/order-desk/HEAD.json"))), "retired")).isEmpty();
		// a reader refuses a publish
		assertThatThrownBy(() -> publish(reader, "order-desk", "1.5.0", "released", Fixtures.bundle("1.4.0")))
			.isInstanceOfSatisfying(BundleStore.Rejected.class, (e) -> assertThat(e.status()).isEqualTo(403));
	}

	@Test
	void aSlotBeingReindexedIsNeverEmptyAndKeepsOnlyItsChunks() throws Exception {
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		publish(s, "order-desk", "1.4.0", "draft", Fixtures.bundle("draft"));
		List<Long> seen = new java.util.ArrayList<>();
		var index = new io.github.shadowx4fox.archindex.index.MemoryVectorIndex() {
			@Override
			public void upsert(List<VectorIndex.Point> batch) {
				seen.add(count("order-desk", "draft"));
				super.upsert(batch);
			}
		};
		reconcile(s, index);
		long chunks = index.count("order-desk", "draft");
		assertThat(chunks).isEqualTo(23);
		// a point of a chunk the draft no longer has, as another instance or an older draft left it
		index.upsert(List.of(new VectorIndex.Point("stale", Fixtures.HashingEmbeddingModel.vector("gone"),
				new SparseEncoder().document("gone"), Map.of("project", "order-desk", "slot", "draft", "chunk_id", "gone"))));
		seen.clear();
		// a restart re-indexes the draft: while it writes, the slot keeps its points; after, only its chunks
		reconcile(s, index);
		assertThat(seen).isNotEmpty().allSatisfy((n) -> assertThat(n).isGreaterThanOrEqualTo(chunks));
		assertThat(index.count("order-desk", "draft")).isEqualTo(chunks);
	}

	private static Indexer reconcile(BundleStore s, VectorIndex index) throws Exception {
		var cfg = new io.github.shadowx4fox.archindex.ArchIndexProperties.Embedding("q: ", "", 1024, 32, 0);
		Indexer indexer = new Indexer(s, index, new Embedder(new Fixtures.HashingEmbeddingModel(), cfg, "stand-in"),
				new SparseEncoder());
		indexer.reconcileAll().get();
		return indexer;
	}

	private static BundleStore.Published publish(BundleStore s, String key, String version, String channel, Path dir) {
		return s.publish(key, version, channel, new ByteArrayInputStream(Fixtures.tgz(dir)));
	}

}
