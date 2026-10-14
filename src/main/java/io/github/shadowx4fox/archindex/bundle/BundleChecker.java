package io.github.shadowx4fox.archindex.bundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The bundle held to its schema before the server keeps it — the same rules as the plugin's
 * {@code export-index.js --check}: every listed file present with its sha256, nothing unlisted, every chunk a UUID with
 * the hash of its text and the manifest's project and version, every pack part on disk with its sha256.
 */
public final class BundleChecker {

	public record Finding(String where, String text) {
	}

	public record Result(boolean pass, int chunks, int buildpacks, List<Finding> findings) {
	}

	static final List<String> KINDS = List.of("overview", "section", "principle", "flow", "phase", "integration",
			"component", "adr", "tdr");

	private static final Pattern UUID = Pattern
		.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

	private BundleChecker() {
	}

	public static Result check(Path dir) {
		List<Finding> f = new ArrayList<>();
		Map<String, Object> m;
		try {
			m = J.obj(Files.readAllBytes(dir.resolve("manifest.json")));
		}
		catch (Exception e) {
			return new Result(false, 0, 0, List.of(new Finding("manifest.json", "unreadable: " + e.getMessage())));
		}
		if (!Bundle.SCHEMA.equals(J.str(m, "schema"))) {
			f.add(new Finding("manifest.json", "schema " + J.str(m, "schema") + ", expected " + Bundle.SCHEMA));
		}
		for (String k : List.of("project", "architecture", "channel", "files", "counts")) {
			if (m.get(k) == null) {
				f.add(new Finding("manifest.json", "missing " + k));
			}
		}
		String channel = J.str(m, "channel");
		if (!"released".equals(channel) && !"draft".equals(channel)) {
			f.add(new Finding("manifest.json", "channel " + channel));
		}
		Set<String> listed = new HashSet<>();
		for (Map<String, Object> x : J.maps(m, "files")) {
			String p = J.str(x, "path");
			listed.add(p);
			Path file = safe(dir, p);
			if (file == null) {
				f.add(new Finding(p, "path leaves the bundle"));
			}
			else if (!Files.isRegularFile(file)) {
				f.add(new Finding(p, "listed, not on disk"));
			}
			else if (!sha(file).equals(J.str(x, "sha256"))) {
				f.add(new Finding(p, "sha256 differs from the manifest"));
			}
		}
		for (String p : listFiles(dir)) {
			if (!p.equals("manifest.json") && !listed.contains(p)) {
				f.add(new Finding(p, "on disk, not in the manifest"));
			}
		}
		String key = J.str(J.map(m, "project"), "key");
		String version = J.str(J.map(m, "architecture"), "version");
		int n = 0;
		Path chunks = dir.resolve("chunks.jsonl");
		if (Files.exists(chunks)) {
			Set<String> ids = new HashSet<>();
			List<String> lines = readLines(chunks);
			for (int i = 0; i < lines.size(); i++) {
				String l = lines.get(i);
				if (l.isBlank()) {
					continue;
				}
				n++;
				String w = "chunks.jsonl:" + (i + 1);
				Map<String, Object> c;
				try {
					c = J.obj(l);
				}
				catch (Exception e) {
					f.add(new Finding(w, "not JSON"));
					continue;
				}
				String id = J.str(c, "id");
				if (id == null || !UUID.matcher(id).matches()) {
					f.add(new Finding(w, "id is not a UUID"));
				}
				else if (!ids.add(id)) {
					f.add(new Finding(w, "duplicate id " + id));
				}
				String text = J.str(c, "text");
				if (text == null || text.isEmpty()) {
					f.add(new Finding(w, "empty text"));
				}
				else if (!J.sha256(text).equals(J.str(c, "content_hash"))) {
					f.add(new Finding(w, "content_hash is not the text's"));
				}
				Map<String, Object> mm = J.map(c, "meta");
				if (!KINDS.contains(J.str(mm, "kind"))) {
					f.add(new Finding(w, "kind " + J.str(mm, "kind")));
				}
				if (key == null || !key.equals(J.str(mm, "project"))) {
					f.add(new Finding(w, "project " + J.str(mm, "project")));
				}
				if (version == null || !version.equals(J.str(mm, "architecture_version"))) {
					f.add(new Finding(w, "architecture_version " + J.str(mm, "architecture_version")));
				}
				if (J.str(mm, "file") == null || !(mm.get("lines") instanceof List)) {
					f.add(new Finding(w, "no file / lines"));
				}
				if (!(mm.get("components") instanceof List) || !(mm.get("adrs") instanceof List)) {
					f.add(new Finding(w, "components / adrs not arrays"));
				}
			}
		}
		else {
			f.add(new Finding("chunks.jsonl", "missing"));
		}
		Object counted = J.map(m, "counts").get("chunks");
		if (counted instanceof Number num && num.intValue() != n) {
			f.add(new Finding("manifest.json", "counts.chunks " + num + ", chunks.jsonl has " + n));
		}
		try {
			J.obj(Files.readAllBytes(dir.resolve("ledger.json")));
		}
		catch (Exception e) {
			f.add(new Finding("ledger.json", "missing or not JSON"));
		}
		List<Map<String, Object>> packs = J.maps(m, "buildpacks");
		for (Map<String, Object> b : packs) {
			String bp = J.str(b, "path");
			Path packFile = safe(dir, bp);
			Map<String, Object> p;
			try {
				p = J.obj(Files.readAllBytes(packFile));
			}
			catch (Exception e) {
				f.add(new Finding(bp, "missing or not JSON"));
				continue;
			}
			Path pd = packFile.getParent();
			Set<String> pids = new HashSet<>();
			for (Map<String, Object> x : J.maps(p, "parts")) {
				pids.add(J.str(x, "id"));
				Path q = safe(pd, J.str(x, "path"));
				if (q == null || !q.normalize().startsWith(dir.normalize())) {
					f.add(new Finding(bp, "part " + J.str(x, "id") + ": path leaves the bundle"));
				}
				else if (!Files.isRegularFile(q)) {
					f.add(new Finding(bp, "part " + J.str(x, "id") + ": " + J.str(x, "path") + " not on disk"));
				}
				else if (!sha(q).equals(J.str(x, "sha256"))) {
					f.add(new Finding(bp, "part " + J.str(x, "id") + ": sha256 differs"));
				}
			}
			for (Map<String, Object> s : J.maps(p, "plan")) {
				for (String id : J.strings(s, "parts")) {
					if (!pids.contains(id)) {
						f.add(new Finding(bp, "plan step " + J.str(s, "step") + " names part " + id + ", not in the pack"));
					}
				}
			}
			if (!pids.contains("spec")) {
				f.add(new Finding(bp, "no spec part"));
			}
			if (version == null || !version.equals(J.str(p, "architecture_version"))) {
				f.add(new Finding(bp, "architecture_version " + J.str(p, "architecture_version")));
			}
		}
		return new Result(f.isEmpty(), n, packs.size(), f);
	}

	/** {@code base/rel}, or null when rel would leave base. */
	static Path safe(Path base, String rel) {
		if (rel == null) {
			return null;
		}
		Path p = base.resolve(rel).normalize();
		return p.startsWith(base.normalize()) ? p : null;
	}

	static String sha(Path file) {
		try {
			return J.sha256(Files.readAllBytes(file));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static List<String> readLines(Path file) {
		try {
			return List.of(Files.readString(file, StandardCharsets.UTF_8).split("\n", -1));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static List<String> listFiles(Path dir) {
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile).map((p) -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
