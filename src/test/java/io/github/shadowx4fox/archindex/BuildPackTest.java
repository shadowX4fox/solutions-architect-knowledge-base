package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.build.BuildPackService;
import io.github.shadowx4fox.archindex.build.Paging;
import io.github.shadowx4fox.archindex.bundle.BundleChecker;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/** A part read through the tool, page by page, is the file as stored: the fallback when there is no download. */
class BuildPackTest {

	@TempDir
	Path tmp;

	/** A byte-order mark, CRLF and LF lines, trailing spaces, characters outside the BMP and two final newlines. */
	static final String AWKWARD = "﻿openapi: 3.1.0\r\ninfo:\r\n  title: \"Hub API — órdenes 🧾\"   \n  description: '𝒜𝒷𝒸 🚚🚚🚚'\n\tx-tab: yes\n\n";

	@Test
	void thePagesOfAPartJoinedAreTheStoredBytes() throws Exception {
		Path b = Fixtures.copy(Fixtures.bundle("1.4.0"), tmp.resolve("b"));
		byte[] bytes = AWKWARD.getBytes(StandardCharsets.UTF_8);
		Files.write(b.resolve("buildpacks/hub-api/assets/openapi.yaml"), bytes);
		Map<String, Object> pack = J.obj(Files.readAllBytes(b.resolve("buildpacks/hub-api/pack.json")));
		pack.put("parts", J.maps(pack, "parts").stream().map((x) -> {
			Map<String, Object> m = new LinkedHashMap<>(x);
			if ("asset:openapi.yaml".equals(J.str(x, "id"))) {
				m.put("sha256", J.sha256(bytes));
				m.put("bytes", bytes.length);
			}
			return m;
		}).toList());
		Fixtures.rewrite(b, "buildpacks/hub-api/pack.json", J.write(pack));
		Fixtures.rewrite(b, "buildpacks/hub-api/assets/openapi.yaml", AWKWARD);
		assertThat(BundleChecker.check(b).findings()).isEmpty();
		BundleStore store = new BundleStore(tmp.resolve("data"), 50_000_000);
		store.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(b)));
		Bundles bundles = new Bundles(store);

		// every page size, so a page ends inside every surrogate pair and between every \r and \n
		for (int max = 1; max <= AWKWARD.length() + 1; max++) {
			BuildPackService packs = new BuildPackService(bundles, max, null);
			StringBuilder joined = new StringBuilder();
			Integer offset = null;
			int pages = 0;
			while (true) {
				Map<String, Object> page = packs.asset("order-desk", "hub-api", "openapi.yaml", "1.4.0", offset);
				String content = J.str(page, "content");
				// each page on its own is valid UTF-8 — a tool result carries it alone
				assertThat(new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(content);
				joined.append(content);
				pages++;
				assertThat(pages).isLessThanOrEqualTo(AWKWARD.length() + 1);
				if (page.get("next_offset") == null) {
					break;
				}
				offset = ((Number) page.get("next_offset")).intValue();
			}
			byte[] got = joined.toString().getBytes(StandardCharsets.UTF_8);
			assertThat(got).as("pages of %d", max).isEqualTo(bytes);
			assertThat(J.sha256(got)).isEqualTo(J.sha256(bytes));
		}

		// the download is the same bytes, with the part's hash; no public URL, no download_url
		BuildPackService packs = new BuildPackService(bundles, 60_000, null);
		BuildPackService.Download d = packs.download("order-desk", "1.4.0", "hub-api", "assets/openapi.yaml", null);
		assertThat(d.bytes()).isEqualTo(bytes);
		assertThat(d.sha256()).isEqualTo(J.sha256(bytes));
		assertThat(J.maps(packs.index("order-desk", "hub-api", "1.4.0"), "parts")).allSatisfy((x) -> assertThat(x).doesNotContainKey("download_url"));
		// only a part's path is served: pack.json, the manifest or a way out of the pack are not parts
		for (String p : new String[] { "pack.json", "../../manifest.json", "assets/../../../ledger.json" }) {
			assertThatThrownBy(() -> packs.download("order-desk", "1.4.0", "hub-api", p, null)).isInstanceOf(ToolException.class);
		}
		assertThatThrownBy(() -> packs.download("order-desk", "1.4.0", "hub-api", "assets/openapi.yaml", "0".repeat(64)))
			.isInstanceOf(BuildPackService.RevisionMoved.class);
	}

	@Test
	void aPageNeverEndsInsideASurrogatePair() {
		String s = "a🧾b";
		assertThat(Paging.end(s, 0, 2)).isEqualTo(1);
		assertThat(Paging.end(s, 1, 1)).isEqualTo(3);   // a page of one character still moves past the pair
		assertThat(Paging.end(s, 0, 3)).isEqualTo(3);
	}

	@Test
	void aDownloadUrlNamesTheSlotAndEncodesEachSegment() throws Exception {
		BundleStore store = new BundleStore(tmp.resolve("data"), 50_000_000);
		store.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("1.4.0"))));
		store.publish("order-desk", "1.4.0", "draft", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("draft"))));
		BuildPackService packs = new BuildPackService(new Bundles(store), 60_000, "https://alia.example/");
		Map<String, String> urls = new LinkedHashMap<>();
		J.maps(packs.index("order-desk", "hub-api", null), "parts").forEach((x) -> urls.put(J.str(x, "id"), J.str(x, "download_url")));
		assertThat(urls.get("asset:openapi.yaml")).isEqualTo("https://alia.example/packs/order-desk/1.4.0/hub-api/assets/openapi.yaml");
		assertThat(urls.get("interface:web-app→hub-api")).isEqualTo("https://alia.example/packs/order-desk/1.4.0/hub-api/interfaces/web-app%E2%86%92hub-api.md");
		assertThat(urls).allSatisfy((id, u) -> assertThat(u).startsWith("https://alia.example/packs/"));
		// the draft carries a release's version number: its URL names the slot
		assertThat(J.str(packs.part("order-desk", "hub-api", "spec", "draft", null), "download_url"))
			.isEqualTo("https://alia.example/packs/order-desk/draft/hub-api/spec.md");
	}

}
