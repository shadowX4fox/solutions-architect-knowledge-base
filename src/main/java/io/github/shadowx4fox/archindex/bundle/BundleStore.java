package io.github.shadowx4fox.archindex.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
 * {data}/bundles/.generation                     changes on every publish: readers reload when it moves
 * {data}/bundles/{key}/HEAD.json                 which directory holds each released version and the draft, and the
 *                                                directories replaced, kept until the retention passes
 * {data}/bundles/{key}/released/{version}~{id}/  a released version — a new directory each time its packs move
 * {data}/bundles/{key}/drafts/{id}/              the draft — a new directory per draft publish
 * {data}/bundles/{key}/revisions/{version}.json  the build packs a second publish replaced, in order, each with its diff
 * </pre>
 *
 * A bundle directory never changes once placed, so an instance still serving it — a reader that has not reloaded yet,
 * a download under way — never sees its files move: a publish writes a new directory and then HEAD.json, and the one
 * replaced is deleted once {@code retain} has passed. Only one instance writes (the writer, {@code archindex.role=all});
 * a reader ({@code readOnly}) refuses a publish and calls {@link #refresh()} to follow the writer. A data directory from
 * before HEAD.json ({@code released/{version}/}, {@code draft/}) is read as it is and taken over by the writer at start.
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

	private static final String HEAD = "HEAD.json";

	private static final String GENERATION = ".generation";

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

		/** Directories replaced, relative to the project's folder, with when: deleted once the retention has passed. */
		final List<Map<String, Object>> retired = new ArrayList<>();

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

	private final boolean readOnly;

	private final Duration retain;

	private volatile String generation;

	public BundleStore(Path dataDir, long maxBytes) {
		this(dataDir, maxBytes, false, Duration.ZERO);
	}

	/**
	 * {@code readOnly}: a reader instance, following the writer through {@link #refresh()}. {@code retain}: how long a
	 * replaced bundle directory stays for instances still serving it — longer than a reader takes to reload.
	 */
	public BundleStore(Path dataDir, long maxBytes, boolean readOnly, Duration retain) {
		this.root = dataDir.toAbsolutePath().normalize();
		this.maxBytes = maxBytes;
		this.readOnly = readOnly;
		this.retain = retain;
		this.generation = readGeneration();
		load();
		if (!readOnly) {
			takeOver();
		}
	}

	public boolean readOnly() {
		return readOnly;
	}

	private Path bundles() {
		return root.resolve("bundles");
	}

	private void load() {
		try {
			Files.createDirectories(bundles());
			for (String key : keysOnDisk()) {
				Project p = read(key, null);
				if (p != null) {
					projects.put(key, p);
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		log.info("bundles: {} projects, {} released versions", projects.size(),
				projects.values().stream().mapToInt((p) -> p.released.size()).sum());
	}

	private List<String> keysOnDisk() throws IOException {
		try (Stream<Path> keys = Files.list(bundles())) {
			return keys.filter(Files::isDirectory)
				.map((k) -> k.getFileName().toString())
				.filter((k) -> KEY.matcher(k).matches())
				.toList();
		}
	}

	/**
	 * One project as its folder holds it: through HEAD.json, or — a folder from before it — by its directories. A bundle
	 * already loaded from the same directory is reused: directories never change.
	 */
	private Project read(String key, Project was) throws IOException {
		Path base = bundles().resolve(key);
		Map<Path, Bundle> known = new java.util.HashMap<>();
		if (was != null) {
			was.released.values().forEach((b) -> known.put(b.dir(), b));
			was.draft().ifPresent((b) -> known.put(b.dir(), b));
		}
		java.util.function.Function<Path, Optional<Bundle>> at = (dir) -> {
			Bundle b = known.get(dir);
			return b != null ? Optional.of(b) : tryLoad(dir);
		};
		Project p = new Project();
		Path head = base.resolve(HEAD);
		if (Files.exists(head)) {
			Map<String, Object> h = J.obj(Files.readAllBytes(head));
			for (Map.Entry<String, Object> e : J.map(h, "released").entrySet()) {
				if (e.getValue() instanceof String rel) {
					at.apply(inside(base, rel)).ifPresent((b) -> p.released.put(b.version(), b));
				}
			}
			String d = J.str(h, "draft");
			if (d != null) {
				at.apply(inside(base, d)).ifPresent((b) -> p.draft = b);
			}
			p.retired.addAll(J.maps(h, "retired"));
		}
		else {
			Path rel = base.resolve("released");
			if (Files.isDirectory(rel)) {
				try (Stream<Path> vs = Files.list(rel)) {
					for (Path v : vs.filter(Files::isDirectory).sorted().toList()) {
						at.apply(v).ifPresent((b) -> p.released.put(b.version(), b));
					}
				}
			}
			Path d = base.resolve(DRAFT);
			if (Files.isDirectory(d)) {
				at.apply(d).ifPresent((b) -> p.draft = b);
			}
		}
		Path rv = base.resolve(REVISIONS);
		if (Files.isDirectory(rv)) {
			try (Stream<Path> fs = Files.list(rv)) {
				for (Path f : fs.filter((x) -> x.getFileName().toString().endsWith(".json")).toList()) {
					String v = f.getFileName().toString().replaceFirst("\\.json$", "");
					p.revisions.put(v, new ArrayList<>(J.maps(J.obj(Files.readAllBytes(f)), "revisions")));
				}
			}
		}
		return p.released.isEmpty() && p.draft == null && was == null && !Files.exists(head) ? null : p;
	}

	private static Path inside(Path base, String rel) {
		Path p = base.resolve(rel).normalize();
		if (!p.startsWith(base)) {
			throw new IllegalStateException("HEAD.json names a directory outside " + base + ": " + rel);
		}
		return p;
	}

	/**
	 * A reader follows the writer: when {@code .generation} moved, every project is read again from its HEAD.json and
	 * swapped in whole. Returns the projects whose bundles or revisions changed, and those gone.
	 */
	public java.util.Set<String> refresh() {
		String now = readGeneration();
		if (Objects.equals(now, generation)) {
			return java.util.Set.of();
		}
		java.util.Set<String> changed = new TreeSet<>();
		try {
			List<String> keys = keysOnDisk();
			for (String key : keys) {
				Project was = projects.get(key);
				Project p = read(key, was);
				if (p == null) {
					continue;
				}
				if (was == null || !same(was, p)) {
					projects.put(key, p);
					changed.add(key);
				}
			}
			for (String key : List.copyOf(projects.keySet())) {
				if (!keys.contains(key)) {
					projects.remove(key);
					changed.add(key);
				}
			}
			generation = now;
		}
		catch (IOException | RuntimeException e) {
			// the writer may be mid-publish on a slow filesystem: keep what is held, try again next time
			log.warn("could not reload the bundles: {}", e.getMessage());
		}
		if (!changed.isEmpty()) {
			log.info("bundles reloaded: {}", changed);
		}
		return changed;
	}

	private static boolean same(Project a, Project b) {
		return a.released.size() == b.released.size()
				&& a.released.entrySet().stream().allMatch((e) -> b.released.get(e.getKey()) == e.getValue())
				&& a.draft == b.draft && a.revisions.equals(b.revisions);
	}

	private String readGeneration() {
		try {
			Path g = bundles().resolve(GENERATION);
			return Files.exists(g) ? Files.readString(g).strip() : null;
		}
		catch (IOException e) {
			return generation;
		}
	}

	/** The writer at start: a HEAD.json for every project without one, and what was left behind cleaned up. */
	private void takeOver() {
		delete(root.resolve(".incoming"));
		projects.forEach((key, p) -> {
			if (!Files.exists(bundles().resolve(key).resolve(HEAD))) {
				writeHead(key, p);
			}
			collect(key, p);
		});
		bumpGeneration();
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
		if (readOnly) {
			throw new Rejected(403, "this instance only reads (archindex.role=reader): publish to the writer", List.of());
		}
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
				Bundle placed = place(incoming, base.resolve("drafts").resolve(id()));
				p.draft = placed;
				retire(p, base, held);
				commit(key, p);
				return Published.stored(placed, held == null || !held.chunkSignature().equals(placed.chunkSignature()),
						version + " stored as the draft of " + key);
			}
			Bundle held = p.released.get(version);
			Published out;
			if (held == null) {
				Bundle placed = place(incoming, base.resolve("released").resolve(version + "~" + id()));
				p.released.put(version, placed);
				out = Published.stored(placed, true, version + " stored (released)");
			}
			else {
				out = republish(p, held, b, incoming, base);
				if (out.outcome() != Outcome.PACKS_UPDATED) {
					return out;
				}
				p.released.put(version, out.bundle());
				retire(p, base, held);
			}
			Bundle d = p.draft;
			if (d != null && Semver.compare(d.version(), version) <= 0) {
				p.draft = null;
				retire(p, base, d);
			}
			commit(key, p);
			return out;
		}
		finally {
			delete(incoming);
		}
	}

	private static String id() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	/** A directory no longer named by HEAD.json, kept until the retention has passed. */
	private static void retire(Project p, Path base, Bundle b) {
		if (b != null) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("path", base.relativize(b.dir()).toString().replace('\\', '/'));
			r.put("at", Instant.now().toString());
			p.retired.add(r);
		}
	}

	/** What a publish changed, made visible: HEAD.json, then the generation readers watch, then what has expired. */
	private void commit(String key, Project p) {
		writeHead(key, p);
		bumpGeneration();
		collect(key, p);
	}

	private void writeHead(String key, Project p) {
		Path base = bundles().resolve(key);
		Map<String, Object> released = new LinkedHashMap<>();
		p.released.forEach((v, b) -> released.put(v, base.relativize(b.dir()).toString().replace('\\', '/')));
		Map<String, Object> h = new LinkedHashMap<>();
		h.put("project", key);
		h.put("released", released);
		h.put("draft", p.draft == null ? null : base.relativize(p.draft.dir()).toString().replace('\\', '/'));
		synchronized (p.retired) {
			h.put("retired", List.copyOf(p.retired));
		}
		writeAtomically(base.resolve(HEAD), J.write(h));
	}

	private void bumpGeneration() {
		String g = Instant.now() + " " + UUID.randomUUID();
		writeAtomically(bundles().resolve(GENERATION), g);
		generation = g;
	}

	private static void writeAtomically(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("." + file.getFileName() + "-" + UUID.randomUUID());
			Files.writeString(tmp, content);
			move(tmp, file);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Deletes the directories replaced longer ago than the retention, and any bundle directory HEAD.json does not know
	 * that is as old — what a crash between placing and committing left.
	 */
	private void collect(String key, Project p) {
		Path base = bundles().resolve(key);
		Instant cutoff = Instant.now().minus(retain);
		java.util.Set<Path> live = new java.util.HashSet<>();
		p.released.values().forEach((b) -> live.add(b.dir()));
		if (p.draft != null) {
			live.add(p.draft.dir());
		}
		boolean dropped = false;
		synchronized (p.retired) {
			for (var it = p.retired.iterator(); it.hasNext();) {
				Map<String, Object> r = it.next();
				Path dir = inside(base, J.str(r, "path"));
				if (live.contains(dir)) {
					it.remove();
					dropped = true;
				}
				else if (Instant.parse(J.str(r, "at")).isBefore(cutoff) || Instant.parse(J.str(r, "at")).equals(cutoff)) {
					delete(dir);
					it.remove();
					dropped = true;
				}
				else {
					live.add(dir);
				}
			}
		}
		List<Path> candidates = new ArrayList<>();
		try {
			for (String sub : List.of("released", "drafts")) {
				Path d = base.resolve(sub);
				if (Files.isDirectory(d)) {
					try (Stream<Path> s = Files.list(d)) {
						s.filter(Files::isDirectory).forEach(candidates::add);
					}
				}
			}
			if (Files.isDirectory(base.resolve(DRAFT))) {
				candidates.add(base.resolve(DRAFT));
			}
			for (Path c : candidates) {
				if (!live.contains(c.normalize()) && !Files.getLastModifiedTime(c).toInstant().isAfter(cutoff)) {
					delete(c);
				}
			}
		}
		catch (IOException e) {
			log.warn("could not clean up {}: {}", base, e.getMessage());
		}
		if (dropped) {
			writeHead(key, p);
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
		Bundle placed = place(incoming, base.resolve("released").resolve(version + "~" + id()));
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

	/** Moves an extracted bundle into a directory of its own, never one in use, and loads it from there. */
	private static Bundle place(Path incoming, Path target) {
		try {
			Files.createDirectories(target.getParent());
			move(incoming, target);
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
