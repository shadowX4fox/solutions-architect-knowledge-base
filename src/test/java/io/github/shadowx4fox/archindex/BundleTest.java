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

class BundleTest {

	@TempDir
	Path tmp;

	@Test
	void everyFixtureBundlePassesTheCheckAsThePluginWroteIt() {
		for (String b : List.of("1.3.0-search", "1.3.0", "1.4.0", "draft")) {
			BundleChecker.Result r = BundleChecker.check(Fixtures.bundle(b));
			assertThat(r.findings()).as(b).isEmpty();
			assertThat(r.chunks()).isEqualTo(22);
		}
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
		assertThat(p.outcome()).isEqualTo(Outcome.PACKS_ADDED);
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
		assertThat(publish(s, "order-desk", "1.4.0", "released", forged).outcome()).isEqualTo(Outcome.CONFLICT);
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

	private static BundleStore.Published publish(BundleStore s, String key, String version, String channel, Path dir) {
		return s.publish(key, version, channel, new ByteArrayInputStream(Fixtures.tgz(dir)));
	}

}
