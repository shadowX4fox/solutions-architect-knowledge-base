package io.github.shadowx4fox.archindex.build;

import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.util.UriUtils;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * A component's build pack, served in parts: the index first (what the pack holds, the ordered plan, flows, interfaces,
 * open questions), then each part byte for byte. A build never goes through embeddings — the agent reads the exact
 * contracts the handoff kit validated. A version without a pack for the component says which versions have one; it
 * never falls back to another version silently. With a public URL set, every part also has a {@code download_url}: the
 * raw bytes over HTTP ({@code PackController}), so an agent saves a contract asset with curl instead of re-typing it.
 */
public class BuildPackService {

	private final Bundles bundles;

	private final int maxPartChars;

	private final String publicUrl;

	public BuildPackService(Bundles bundles, int maxPartChars, String publicUrl) {
		this.bundles = bundles;
		this.maxPartChars = maxPartChars;
		this.publicUrl = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.strip().replaceAll("/+$", "");
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
		// the sha256 of pack.json: it moves when a second publish of this version replaced the pack
		m.put("pack_revision", l.bundle().packRevision(l.slug()).orElse(null));
		List<Map<String, Object>> parts = new ArrayList<>();
		for (Map<String, Object> x : J.maps(p, "parts")) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("id", x.get("id"));
			e.put("kind", x.get("kind"));
			e.put("title", x.get("title"));
			e.put("bytes", x.get("bytes"));
			e.put("sha256", x.get("sha256"));
			downloadUrl(l, x).ifPresent((u) -> e.put("download_url", u));
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

	/** One part's file, as stored — what {@code GET /packs/{key}/{version}/{slug}/{path}} serves. */
	public record Download(String project, String version, String slug, String packRevision, String path, String sha256,
			boolean draft, byte[] bytes) {
	}

	/**
	 * The bytes of the part whose {@code path} in pack.json is {@code path}. With {@code revision}, a pack replaced since
	 * is a {@link RevisionMoved}: the bytes would not be the ones the agent recorded.
	 */
	public Download download(String project, String version, String component, String path, String revision) {
		Located l = locate(project, component, version);
		String current = l.bundle().packRevision(l.slug()).orElse(null);
		if (revision != null && !revision.isBlank() && !revision.strip().equals(current)) {
			throw new RevisionMoved(current);
		}
		Map<String, Object> part = J.maps(l.pack(), "parts")
			.stream()
			.filter((x) -> path != null && path.equals(J.str(x, "path")))
			.findFirst()
			.orElseThrow(() -> new ToolException("no part at '" + path + "' in the pack of " + l.slug() + "; paths: "
					+ String.join(", ", J.maps(l.pack(), "parts").stream().map((x) -> J.str(x, "path")).toList())));
		try {
			return new Download(l.bundle().projectKey(), l.bundle().version(), l.slug(), current, path,
					J.str(part, "sha256"), !l.bundle().released(), Files.readAllBytes(file(l, part)));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The pack was replaced since the revision a download named; carries the current one. */
	public static class RevisionMoved extends RuntimeException {

		@Serial
		private static final long serialVersionUID = 1L;

		private final String current;

		RevisionMoved(String current) {
			super("the pack was replaced: its revision is now " + current);
			this.current = current;
		}

		public String current() {
			return current;
		}

	}

	private Optional<String> downloadUrl(Located l, Map<String, Object> part) {
		String path = J.str(part, "path");
		if (publicUrl == null || path == null) {
			return Optional.empty();
		}
		StringBuilder u = new StringBuilder(publicUrl).append("/packs");
		// the draft shares its version number with a release: its slot is "draft"
		for (String seg : List.of(l.bundle().projectKey(), l.bundle().released() ? l.bundle().version() : "draft", l.slug())) {
			u.append('/').append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
		}
		for (String seg : path.split("/")) {
			u.append('/').append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
		}
		return Optional.of(u.toString());
	}

	private static Path file(Located l, Map<String, Object> part) {
		Path dir = l.bundle().packDir(l.slug());
		String rel = J.str(part, "path");
		Path file = rel == null ? null : dir.resolve(rel).normalize();
		if (file == null || !file.startsWith(dir)) {
			throw new ToolException("part path leaves the pack");
		}
		return file;
	}

	private Map<String, Object> read(Located l, Map<String, Object> part, Integer offset) {
		Path file = file(l, part);
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
		m.put("pack_revision", l.bundle().packRevision(l.slug()).orElse(null));
		m.put("part", part.get("id"));
		m.put("kind", part.get("kind"));
		m.put("title", part.get("title"));
		m.put("file", Optional.ofNullable(J.str(part, "path")).map((p) -> p.substring(p.lastIndexOf('/') + 1)).orElse(null));
		m.put("sha256", part.get("sha256"));
		downloadUrl(l, part).ifPresent((u) -> m.put("download_url", u));
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
