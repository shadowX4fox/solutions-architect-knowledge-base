package io.github.shadowx4fox.archindex.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.shadowx4fox.archindex.changes.BundleDiff;

/**
 * The bundles the server holds, on disk and in memory. Layout:
 *
 * <pre>
 * {data}/bundles/{key}/released/{version}/   one per released version
 * {data}/bundles/{key}/draft/                at most one draft per project, replaced by each draft publish
 * {data}/bundles/{key}/revisions/{version}.json  the build packs a second publish replaced, in order, each with its diff
 * </pre>
 *
 * A released version is immutable in what search serves. A second publish of it (the second phase of a release, when
 * the regenerated kit is committed) carries the same chunks, the same ledger and every build pack held for it; it may
 * add packs and replace them — a pack is replaced when its revision, the sha256 of its pack.json, moved. Anything else
 * is refused with its reason, and what is held stays. The bundle held is replaced, so each replacement is kept apart
 * as a revision — the packs' revisions on both sides and {@code diffBundles(held, incoming)} — for {@code what_changed}
 * to answer a developer who built from an earlier revision of the same version.
 */
public class BundleStore {

	private static final Logger log = LoggerFactory.getLogger(BundleStore.class);

	public static final String DRAFT = "draft";

	private static final String REVISIONS = "revisions";

	private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,99}$");

	public enum Outcome {

		/** 201: a version (or a draft) the server did not hold. */
		STORED,
		/** 200: the released version held, build packs added or replaced by this publish. */
		PACKS_UPDATED,
		/** 200: the released version held, nothing new — no packs in this publish, or the same ones; the held packs stay. */
		UNCHANGED,
		/** 409: refused — {@link Conflict} says why; what is held stays. */
		CONFLICT

	}

	/** Why a second publish of a released version is refused: the {@code reason} of the 409 body. */
	public enum Conflict {

		CHUNKS_DIFFER, LEDGER_DIFFERS, PACKS_MISSING;

		public String reason() {
			return name().toLowerCase();
		}

	}

	/**
	 * What a publish did. {@code added}, {@code replaced} and {@code unchanged} name the build packs of a released
	 * version held (empty for a version stored new); on a conflict, {@code conflict} and {@code conflictPacks} say why.
	 */
	public record Published(Outcome outcome, Bundle bundle, boolean chunksChanged, String message, List<String> added,
			List<String> replaced, List<String> unchanged, Conflict conflict, List<String> conflictPacks) {

		static Published stored(Bundle b, boolean chunksChanged, String message) {
			return new Published(Outcome.STORED, b, chunksChanged, message, List.of(), List.of(), List.of(), null, List.of());
		}

		static Published refused(Bundle held, Conflict why, List<String> packs, String message) {
			return new Published(Outcome.CONFLICT, held, false, message, List.of(), List.of(), List.of(), why, packs);
		}

	}

	public static class Rejected extends RuntimeException {

		@Serial
		private static final long serialVersionUID = 1L;

		private final int status;

		private final transient List<BundleChecker.Finding> findings;

		public Rejected(int status, String message, List<BundleChecker.Finding> findings) {
			super(message);
			this.status = status;
			this.findings = findings;
		}

		public int status() {
			return status;
		}

		public List<BundleChecker.Finding> findings() {
			return findings == null ? List.of() : findings;
		}

	}

	/** One project: its released versions in semver order, and its draft. */
	public static final class Project {

		final NavigableMap<String, Bundle> released = new ConcurrentSkipListMap<>(Semver.ORDER);

		volatile Bundle draft;

		final Map<String, List<Map<String, Object>>> revisions = new ConcurrentHashMap<>();

		public NavigableMap<String, Bundle> released() {
			return released;
		}

		public Optional<Bundle> draft() {
			return Optional.ofNullable(draft);
		}

		public Optional<Bundle> latest() {
			return released.isEmpty() ? Optional.empty() : Optional.of(released.lastEntry().getValue());
		}

		/**
		 * The pack replacements of a released version, oldest first: {@code at}, {@code packs} ({slug: {from, to}} — the
		 * revisions on both sides) and {@code changes} (the same-version diff).
		 */
		public List<Map<String, Object>> revisions(String version) {
			return List.copyOf(revisions.getOrDefault(version, List.of()));
		}

	}

	private final Path root;

	private final long maxBytes;

	private final Map<String, Project> projects = new ConcurrentHashMap<>();

	public BundleStore(Path dataDir, long maxBytes) {
		this.root = dataDir.toAbsolutePath().normalize();
		this.maxBytes = maxBytes;
		load();
	}

	private Path bundles() {
		return root.resolve("bundles");
	}

	private void load() {
		try {
			Files.createDirectories(bundles());
			try (Stream<Path> keys = Files.list(bundles())) {
				for (Path k : keys.filter(Files::isDirectory).toList()) {
					String key = k.getFileName().toString();
					Path rel = k.resolve("released");
					if (Files.isDirectory(rel)) {
						try (Stream<Path> vs = Files.list(rel)) {
							for (Path v : vs.filter(Files::isDirectory).toList()) {
								tryLoad(v).ifPresent((b) -> project(key).released.put(b.version(), b));
							}
						}
					}
					Path d = k.resolve(DRAFT);
					if (Files.isDirectory(d)) {
						tryLoad(d).ifPresent((b) -> project(key).draft = b);
					}
					Path rv = k.resolve(REVISIONS);
					if (Files.isDirectory(rv)) {
						try (Stream<Path> fs = Files.list(rv)) {
							for (Path f : fs.filter((x) -> x.getFileName().toString().endsWith(".json")).toList()) {
								String v = f.getFileName().toString().replaceFirst("\\.json$", "");
								project(key).revisions.put(v, new ArrayList<>(J.maps(J.obj(Files.readAllBytes(f)), "revisions")));
							}
						}
					}
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		log.info("bundles: {} projects, {} released versions", projects.size(),
				projects.values().stream().mapToInt((p) -> p.released.size()).sum());
	}

	private Optional<Bundle> tryLoad(Path dir) {
		try {
			return Optional.of(Bundle.load(dir));
		}
		catch (RuntimeException e) {
			log.warn("skipping {}: {}", dir, e.getMessage());
			return Optional.empty();
		}
	}

	private Project project(String key) {
		return projects.computeIfAbsent(key, (k) -> new Project());
	}

	public Map<String, Project> projects() {
		return projects;
	}

	public Optional<Project> find(String key) {
		return Optional.ofNullable(projects.get(key));
	}

	/**
	 * The bundle a tool reads: {@code version} null or "latest" → the newest released, "draft" → the draft, else that
	 * released version.
	 */
	public Optional<Bundle> resolve(String key, String version) {
		Project p = projects.get(key);
		if (p == null) {
			return Optional.empty();
		}
		if (version == null || version.isBlank() || version.equals("latest")) {
			return p.latest();
		}
		if (version.equals(DRAFT)) {
			return p.draft();
		}
		return Optional.ofNullable(p.released.get(version));
	}

	/** The newest released bundle strictly older than {@code version}: what an export diffs against. */
	public Optional<Bundle> before(String key, String version) {
		Project p = projects.get(key);
		if (p == null || !Semver.valid(version)) {
			return Optional.empty();
		}
		return Optional.ofNullable(p.released.lowerEntry(version)).map(Map.Entry::getValue);
	}

	public synchronized Published publish(String key, String version, String channel, InputStream gz) {
		if (key == null || !KEY.matcher(key).matches()) {
			throw new Rejected(400, "project key must be lowercase kebab-case: " + key, List.of());
		}
		if (!Semver.valid(version)) {
			throw new Rejected(400, "version must be MAJOR.MINOR.PATCH: " + version, List.of());
		}
		if (!"released".equals(channel) && !DRAFT.equals(channel)) {
			throw new Rejected(400, "channel must be released or draft: " + channel, List.of());
		}
		Path incoming = root.resolve(".incoming").resolve(UUID.randomUUID().toString());
		try {
			try {
				Tgz.extract(gz, incoming, maxBytes);
			}
			catch (Tgz.TooLarge e) {
				throw new Rejected(413, e.getMessage(), List.of());
			}
			catch (IOException e) {
				throw new Rejected(400, "not a gzipped tar: " + e.getMessage(), List.of());
			}
			BundleChecker.Result check = BundleChecker.check(incoming);
			if (!check.pass()) {
				throw new Rejected(422, "the bundle failed its check (" + check.findings().size() + " findings)",
						check.findings());
			}
			Bundle b = Bundle.load(incoming);
			if (!key.equals(b.projectKey())) {
				throw new Rejected(400, "the bundle is project " + b.projectKey() + ", the URL names " + key, List.of());
			}
			if (!version.equals(b.version())) {
				throw new Rejected(400, "the bundle is version " + b.version() + ", the URL names " + version, List.of());
			}
			if (!channel.equals(b.channel())) {
				throw new Rejected(400, "the bundle is channel " + b.channel() + ", the URL names " + channel, List.of());
			}
			Project p = project(key);
			Path base = bundles().resolve(key);
			if (DRAFT.equals(channel)) {
				Bundle held = p.draft;
				Bundle placed = place(incoming, base.resolve(DRAFT));
				p.draft = placed;
				return Published.stored(placed, held == null || !held.chunkSignature().equals(placed.chunkSignature()),
						version + " stored as the draft of " + key);
			}
			Bundle held = p.released.get(version);
			Published out;
			if (held == null) {
				Bundle placed = place(incoming, base.resolve("released").resolve(version));
				p.released.put(version, placed);
				out = Published.stored(placed, true, version + " stored (released)");
			}
			else {
				out = republish(p, held, b, incoming, base);
				if (out.outcome() != Outcome.PACKS_UPDATED) {
					return out;
				}
				p.released.put(version, out.bundle());
			}
			Bundle d = p.draft;
			if (d != null && Semver.compare(d.version(), version) <= 0) {
				p.draft = null;
				delete(base.resolve(DRAFT));
			}
			return out;
		}
		finally {
			delete(incoming);
		}
	}

	/**
	 * A second publish of the released version {@code held}: the same chunks, the same ledger and every pack held, or
	 * refused. Without packs, or with the same ones, nothing moves. Otherwise the incoming bundle — a superset of the
	 * packs held — replaces the version whole, so the server never writes a manifest of its own.
	 */
	private Published republish(Project p, Bundle held, Bundle b, Path incoming, Path base) {
		String version = held.version();
		if (!held.chunkSignature().equals(b.chunkSignature())) {
			return Published.refused(held, Conflict.CHUNKS_DIFFER, List.of(), "released " + version
					+ " is held with other chunks — a released version does not change; release a new version");
		}
		if (!J.same(held.ledgerFacts(), b.ledgerFacts())) {
			return Published.refused(held, Conflict.LEDGER_DIFFERS, List.of(), "released " + version
					+ " is held with another ledger — publish the same commit with the same plugin (SA_PLUGIN_REF), or release a new version");
		}
		if (b.packs().isEmpty()) {
			return new Published(Outcome.UNCHANGED, held, false,
					version + " held with the same chunks; its " + held.packs().size() + " build packs kept", List.of(),
					List.of(), List.copyOf(held.packs().keySet()), null, List.of());
		}
		List<String> missing = held.packs().keySet().stream().filter((s) -> !b.packs().containsKey(s)).sorted().toList();
		if (!missing.isEmpty()) {
			return Published.refused(held, Conflict.PACKS_MISSING, missing, "released " + version
					+ " holds build packs this publish leaves out: " + String.join(", ", missing)
					+ " — regenerate them or fix the kit (kit-check.js)");
		}
		List<String> added = new ArrayList<>();
		List<String> replaced = new ArrayList<>();
		List<String> unchanged = new ArrayList<>();
		for (String s : new TreeSet<>(b.packs().keySet())) {
			if (!held.packs().containsKey(s)) {
				added.add(s);
			}
			else if (!Objects.equals(held.packRevision(s).orElse(null), b.packRevision(s).orElse(null))) {
				replaced.add(s);
			}
			else {
				unchanged.add(s);
			}
		}
		if (added.isEmpty() && replaced.isEmpty()) {
			return new Published(Outcome.UNCHANGED, held, false, version + " held with the same build packs", List.of(),
					List.of(), unchanged, null, List.of());
		}
		if (!replaced.isEmpty()) {
			record(p, base, held, b, replaced);
		}
		Bundle placed = place(incoming, base.resolve("released").resolve(version));
		log.info("released {} {}: build packs added {}, replaced {}", placed.projectKey(), version, added, replaced);
		return new Published(Outcome.PACKS_UPDATED, placed, false, version + " updated — build packs added: "
				+ none(added) + " · replaced: " + none(replaced) + " · unchanged: " + none(unchanged), added, replaced,
				unchanged, null, List.of());
	}

	/** Appends a replacement to the version's revisions, written before the bundle moves, so no replaced pack goes unrecorded. */
	private void record(Project p, Path base, Bundle held, Bundle b, List<String> replaced) {
		Map<String, Object> packs = new LinkedHashMap<>();
		for (String s : replaced) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("from", held.packRevision(s).orElse(null));
			r.put("to", b.packRevision(s).orElse(null));
			packs.put(s, r);
		}
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("at", Instant.now().toString());
		entry.put("packs", packs);
		entry.put("changes", BundleDiff.diff(held, b, null, false));
		List<Map<String, Object>> all = new ArrayList<>(p.revisions.getOrDefault(held.version(), List.of()));
		all.add(entry);
		Map<String, Object> file = new LinkedHashMap<>();
		file.put("project", held.projectKey());
		file.put("version", held.version());
		file.put("revisions", all);
		try {
			Path dir = Files.createDirectories(base.resolve(REVISIONS));
			Path tmp = dir.resolve("." + held.version() + "-" + UUID.randomUUID());
			Files.writeString(tmp, J.write(file));
			move(tmp, dir.resolve(held.version() + ".json"));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		p.revisions.put(held.version(), all);
	}

	private static String none(List<String> l) {
		return l.isEmpty() ? "none" : String.join(", ", l);
	}

	/** Moves an extracted bundle into place, replacing what was there, and loads it from its new home. */
	private Bundle place(Path incoming, Path target) {
		try {
			Files.createDirectories(target.getParent());
			Path old = null;
			if (Files.exists(target)) {
				old = target.resolveSibling(target.getFileName() + ".old-" + UUID.randomUUID());
				move(target, old);
			}
			move(incoming, target);
			if (old != null) {
				delete(old);
			}
			return Bundle.load(target);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static void delete(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			List<Path> all = new ArrayList<>(s.toList());
			all.sort(Comparator.reverseOrder());
			for (Path x : all) {
				Files.deleteIfExists(x);
			}
		}
		catch (IOException e) {
			log.warn("could not delete {}: {}", dir, e.getMessage());
		}
	}

}
