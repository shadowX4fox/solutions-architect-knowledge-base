package io.github.shadowx4fox.archindex.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bundles the server holds, on disk and in memory. Layout:
 *
 * <pre>
 * {data}/bundles/{key}/released/{version}/   one per released version
 * {data}/bundles/{key}/draft/                at most one draft per project, replaced by each draft publish
 * </pre>
 *
 * A released version is immutable in what search serves: a second publish of it may only add build packs (the
 * second phase of a release, when the regenerated kit is committed); different chunks are refused.
 */
public class BundleStore {

	private static final Logger log = LoggerFactory.getLogger(BundleStore.class);

	public static final String DRAFT = "draft";

	private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,99}$");

	public enum Outcome {

		/** 201: a version (or a draft) the server did not hold. */
		STORED,
		/** 200: the released version held, with the build packs this publish carries. */
		PACKS_ADDED,
		/** 200: the released version held, same chunks, no packs in this publish — the held packs stay. */
		UNCHANGED,
		/** 409: the released version held has other chunks. */
		CONFLICT

	}

	public record Published(Outcome outcome, Bundle bundle, boolean chunksChanged, String message) {
	}

	public static class Rejected extends RuntimeException {

		private final int status;

		private final List<BundleChecker.Finding> findings;

		public Rejected(int status, String message, List<BundleChecker.Finding> findings) {
			super(message);
			this.status = status;
			this.findings = findings;
		}

		public int status() {
			return status;
		}

		public List<BundleChecker.Finding> findings() {
			return findings;
		}

	}

	/** One project: its released versions in semver order, and its draft. */
	public static final class Project {

		final NavigableMap<String, Bundle> released = new ConcurrentSkipListMap<>(Semver.ORDER);

		volatile Bundle draft;

		public NavigableMap<String, Bundle> released() {
			return released;
		}

		public Optional<Bundle> draft() {
			return Optional.ofNullable(draft);
		}

		public Optional<Bundle> latest() {
			return released.isEmpty() ? Optional.empty() : Optional.of(released.lastEntry().getValue());
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
				return new Published(Outcome.STORED, placed,
						held == null || !held.chunkSignature().equals(placed.chunkSignature()),
						version + " stored as the draft of " + key);
			}
			Bundle held = p.released.get(version);
			Published out;
			if (held == null) {
				Bundle placed = place(incoming, base.resolve("released").resolve(version));
				p.released.put(version, placed);
				out = new Published(Outcome.STORED, placed, true, version + " stored (released)");
			}
			else if (!held.chunkSignature().equals(b.chunkSignature())) {
				return new Published(Outcome.CONFLICT, held, false, "released " + version
						+ " is held with other chunks — a released version does not change; release a new version");
			}
			else if (b.packs().isEmpty()) {
				return new Published(Outcome.UNCHANGED, held, false,
						version + " held with the same chunks; its " + held.packs().size() + " build packs kept");
			}
			else {
				Bundle placed = place(incoming, base.resolve("released").resolve(version));
				p.released.put(version, placed);
				out = new Published(Outcome.PACKS_ADDED, placed, false,
						version + " updated — build packs: " + String.join(", ", placed.packs().keySet()));
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
			Files.move(from, to);
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
