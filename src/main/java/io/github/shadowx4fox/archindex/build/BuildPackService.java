package io.github.shadowx4fox.archindex.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * A component's build pack, served in parts: the index first (what the pack holds, the ordered plan, flows, interfaces,
 * open questions), then each part byte for byte. A build never goes through embeddings — the agent reads the exact
 * contracts the handoff kit validated. A version without a pack for the component says which versions have one; it
 * never falls back to another version silently.
 */
public class BuildPackService {

	private final Bundles bundles;

	private final int maxPartChars;

	public BuildPackService(Bundles bundles, int maxPartChars) {
		this.bundles = bundles;
		this.maxPartChars = maxPartChars;
	}

	record Located(Bundle bundle, String slug, Map<String, Object> pack) {
	}

	Located locate(String project, String component, String version) {
		Bundle b = bundles.bundle(project, version);
		String slug = J.str(bundles.component(b, component), "slug");
		Map<String, Object> pack = b.pack(slug).orElseThrow(() -> {
			String reason = b.skipped()
				.stream()
				.filter((s) -> slug.equals(J.str(s, "component")))
				.map((s) -> J.str(s, "reason"))
				.findFirst()
				.orElse("no handoff kit entry");
			List<String> with = bundles.store()
				.find(project)
				.map((p) -> p.released().values().stream().filter((x) -> x.packs().containsKey(slug)).map(Bundle::version).toList())
				.orElse(List.of());
			return new ToolException("no build pack for " + slug + " in " + b.projectKey() + " " + b.version() + " ("
					+ reason + "). " + (with.isEmpty() ? "No released version has one yet: the handoff kit for it has to be regenerated and committed."
							: "Versions with one: " + String.join(", ", with) + " — build against the version your .architecture.json names."));
		});
		return new Located(b, slug, pack);
	}

	public Map<String, Object> index(String project, String component, String version) {
		Located l = locate(project, component, version);
		Map<String, Object> p = l.pack();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", l.bundle().projectKey());
		m.put("architecture_version", l.bundle().version());
		m.put("channel", l.bundle().channel());
		m.put("component", p.get("component"));
		List<Map<String, Object>> parts = new ArrayList<>();
		for (Map<String, Object> x : J.maps(p, "parts")) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("id", x.get("id"));
			e.put("kind", x.get("kind"));
			e.put("title", x.get("title"));
			e.put("bytes", x.get("bytes"));
			e.put("sha256", x.get("sha256"));
			parts.add(e);
		}
		m.put("parts", parts);
		m.put("plan", p.get("plan"));
		m.put("interfaces", p.get("interfaces"));
		m.put("flows", p.get("flows"));
		m.put("adrs", p.get("adrs"));
		m.put("open_questions", p.get("open_questions"));
		m.put("next", "get_build_instructions first (the rules and the binding), then get_build_part for each part in plan order");
		return m;
	}

	public Map<String, Object> part(String project, String component, String partId, String version, Integer offset) {
		Located l = locate(project, component, version);
		Map<String, Object> part = J.maps(l.pack(), "parts")
			.stream()
			.filter((x) -> partId != null && partId.equals(J.str(x, "id")))
			.findFirst()
			.orElseThrow(() -> new ToolException("no part '" + partId + "' in the pack of " + l.slug() + "; parts: "
					+ String.join(", ", J.maps(l.pack(), "parts").stream().map((x) -> J.str(x, "id")).toList())));
		return read(l, part, offset);
	}

	public Map<String, Object> asset(String project, String component, String asset, String version, Integer offset) {
		String id = asset == null ? "" : asset.startsWith("asset:") ? asset : "asset:" + asset;
		return part(project, component, id, version, offset);
	}

	public Map<String, Object> instructions(String project, String component, String version) {
		return part(project, component, "instructions", version, null);
	}

	private Map<String, Object> read(Located l, Map<String, Object> part, Integer offset) {
		Path dir = l.bundle().packDir(l.slug());
		String rel = J.str(part, "path");
		Path file = rel == null ? null : dir.resolve(rel).normalize();
		if (file == null || !file.startsWith(dir)) {
			throw new ToolException("part path leaves the pack");
		}
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		int from = Math.max(0, offset == null ? 0 : offset);
		if (from > text.length()) {
			throw new ToolException("offset " + from + " is past the end (" + text.length() + " characters)");
		}
		int to = Paging.end(text, from, maxPartChars);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", l.bundle().projectKey());
		m.put("architecture_version", l.bundle().version());
		m.put("component", l.slug());
		m.put("part", part.get("id"));
		m.put("kind", part.get("kind"));
		m.put("title", part.get("title"));
		m.put("file", Optional.ofNullable(J.str(part, "path")).map((p) -> p.substring(p.lastIndexOf('/') + 1)).orElse(null));
		m.put("sha256", part.get("sha256"));
		m.put("content", text.substring(from, to));
		if (from > 0 || to < text.length()) {
			m.put("range", Map.of("from", from, "to", to, "of", text.length()));
			if (to < text.length()) {
				m.put("next_offset", to);
			}
		}
		return m;
	}

}
