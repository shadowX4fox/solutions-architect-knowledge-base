package io.github.shadowx4fox.archindex.bundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One sa-index/1 bundle on disk, loaded: the manifest, the chunks, the ledger, the build packs and changes.json when
 * the export carried one. Immutable once loaded; a new publish replaces the whole object.
 */
public final class Bundle {

	public static final String SCHEMA = "sa-index/1";

	private final Path dir;

	private final Map<String, Object> manifest;

	private final List<Chunk> chunks;

	private final Map<String, Chunk> chunksById;

	private final Map<String, Object> ledger;

	private final Map<String, Map<String, Object>> packs;

	private final Map<String, Object> changes;

	private final String chunkSignature;

	private Bundle(Path dir, Map<String, Object> manifest, List<Chunk> chunks, Map<String, Object> ledger,
			Map<String, Map<String, Object>> packs, Map<String, Object> changes) {
		this.dir = dir;
		this.manifest = manifest;
		this.chunks = List.copyOf(chunks);
		Map<String, Chunk> byId = new LinkedHashMap<>();
		chunks.forEach((c) -> byId.put(c.id(), c));
		this.chunksById = Collections.unmodifiableMap(byId);
		this.ledger = ledger;
		this.packs = Collections.unmodifiableMap(packs);
		this.changes = changes;
		StringBuilder sb = new StringBuilder();
		chunks.stream().map((c) -> c.id() + ":" + c.contentHash()).sorted().forEach((x) -> sb.append(x).append('\n'));
		this.chunkSignature = J.sha256(sb.toString());
	}

	public static Bundle load(Path dir) {
		try {
			Map<String, Object> m = J.obj(Files.readAllBytes(dir.resolve("manifest.json")));
			Map<String, Object> ledger = J.obj(Files.readAllBytes(dir.resolve("ledger.json")));
			List<Chunk> chunks = readChunks(dir.resolve("chunks.jsonl"));
			Map<String, Map<String, Object>> packs = new LinkedHashMap<>();
			for (Map<String, Object> b : J.maps(m, "buildpacks")) {
				packs.put(J.str(b, "component"), J.obj(Files.readAllBytes(dir.resolve(J.str(b, "path")))));
			}
			Path ch = dir.resolve("changes.json");
			Map<String, Object> changes = Files.exists(ch) ? J.obj(Files.readAllBytes(ch)) : null;
			return new Bundle(dir, m, chunks, ledger, packs, changes);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@SuppressWarnings("unchecked")
	static List<Chunk> readChunks(Path file) throws IOException {
		List<Chunk> out = new ArrayList<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank()) {
				continue;
			}
			Map<String, Object> c = J.obj(line);
			Object meta = c.get("meta");
			out.add(new Chunk(J.str(c, "id"), J.str(c, "content_hash"), J.str(c, "text"),
					meta instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of()));
		}
		return out;
	}

	public Path dir() {
		return dir;
	}

	public Map<String, Object> manifest() {
		return manifest;
	}

	public String projectKey() {
		return J.str(J.map(manifest, "project"), "key");
	}

	public String projectName() {
		return J.str(J.map(manifest, "project"), "name");
	}

	public String version() {
		return J.str(J.map(manifest, "architecture"), "version");
	}

	public String channel() {
		return J.str(manifest, "channel");
	}

	public boolean released() {
		return "released".equals(channel());
	}

	public String language() {
		return J.str(J.map(manifest, "architecture"), "language");
	}

	public List<Chunk> chunks() {
		return chunks;
	}

	public Optional<Chunk> chunk(String id) {
		return Optional.ofNullable(chunksById.get(id));
	}

	public Map<String, Object> ledger() {
		return ledger;
	}

	public Map<String, Map<String, Object>> packs() {
		return packs;
	}

	public Optional<Map<String, Object>> pack(String slug) {
		return Optional.ofNullable(packs.get(slug));
	}

	public Optional<Map<String, Object>> changes() {
		return Optional.ofNullable(changes);
	}

	/** The directory a pack's part paths are relative to: the pack file's own, as the manifest places it. */
	public Path packDir(String slug) {
		return J.maps(manifest, "buildpacks")
			.stream()
			.filter((b) -> slug.equals(J.str(b, "component")) && J.str(b, "path") != null)
			.map((b) -> dir.resolve(J.str(b, "path")).normalize().getParent())
			.findFirst()
			.orElseGet(() -> dir.resolve("buildpacks").resolve(slug).normalize());
	}

	/** The sha256 the manifest lists for a file of the bundle, as the checker verified it. */
	public Optional<String> fileSha(String path) {
		return J.maps(manifest, "files")
			.stream()
			.filter((f) -> path.equals(J.str(f, "path")))
			.map((f) -> J.str(f, "sha256"))
			.filter((s) -> s != null)
			.findFirst();
	}

	/**
	 * A pack's revision: the sha256 the manifest lists for its pack.json ({@code buildpacks/<slug>/pack.json}) —
	 * pack.json lists every part's hash, so any part moving moves it (the plugin's {@code revisionOf}).
	 */
	public Optional<String> packRevision(String slug) {
		String path = J.maps(manifest, "buildpacks")
			.stream()
			.filter((b) -> slug.equals(J.str(b, "component")) && J.str(b, "path") != null)
			.map((b) -> J.str(b, "path"))
			.findFirst()
			.orElse("buildpacks/" + slug + "/pack.json");
		return fileSha(path);
	}

	/**
	 * The ledger without what is not data: the plugin's ledger carried the run's clock until v8.150.1, so a version held
	 * from an older export still compares equal to the same commit exported again.
	 */
	public Map<String, Object> ledgerFacts() {
		Map<String, Object> l = new LinkedHashMap<>(ledger);
		l.remove("generated_at");
		l.remove("project_root");
		return l;
	}

	/** The chunk set as (id, content hash) pairs: two bundles with the same set serve the same search. */
	public String chunkSignature() {
		return chunkSignature;
	}

	public List<Map<String, Object>> skipped() {
		return J.maps(manifest, "skipped");
	}

}
