package io.github.shadowx4fox.archindex.index;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.Chunk;
import io.github.shadowx4fox.archindex.bundle.J;

/**
 * Keeps the vector index equal to the bundles held: every released version and every draft is a slot, indexed once
 * per chunk set. A chunk whose text another slot of the project already embedded reuses that vector — the ids hold
 * across releases and only an edited chunk changes its hash, so a release re-embeds only what it changed. Runs on one
 * background thread; search reports a slot that is not ready instead of answering from half an index.
 */
public class Indexer implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(Indexer.class);

	public enum Status {

		PENDING, INDEXING, READY, FAILED

	}

	public record SlotState(Status status, String signature, int chunks, int embedded, int reused, String error,
			Instant at) {
	}

	private final BundleStore store;

	private final VectorIndex index;

	private final Embedder embedder;

	private final SparseEncoder sparse;

	private final Map<String, SlotState> states = new ConcurrentHashMap<>();

	private final ExecutorService worker = Executors.newSingleThreadExecutor((r) -> {
		Thread t = new Thread(r, "archindex-indexer");
		t.setDaemon(true);
		return t;
	});

	public Indexer(BundleStore store, VectorIndex index, Embedder embedder, SparseEncoder sparse) {
		this.store = store;
		this.index = index;
		this.embedder = embedder;
		this.sparse = sparse;
	}

	public static String slot(Bundle b) {
		return b.released() ? b.version() : BundleStore.DRAFT;
	}

	private static String key(String project, String slot) {
		return project + "/" + slot;
	}

	public SlotState state(String project, String slot) {
		return states.get(key(project, slot));
	}

	public Map<String, SlotState> states() {
		return Map.copyOf(states);
	}

	/** Brings every slot of every project in line with the store, in the background. */
	public Future<?> reconcileAll() {
		List<String> keys = new ArrayList<>(store.projects().keySet());
		keys.forEach((k) -> markPending(k));
		return worker.submit(() -> {
			try {
				index.ensure();
				log.info("vector index: {}", index.describe());
			}
			catch (RuntimeException e) {
				// the store cannot be used as configured (unreachable, or made for another model): every slot says why
				log.error("vector index unavailable: {}", e.getMessage());
				states.replaceAll((k, s) -> new SlotState(Status.FAILED, s.signature(), s.chunks(), 0, 0, e.getMessage(), Instant.now()));
				return;
			}
			keys.forEach((k) -> reconcileNow(k, true));
		});
	}

	public Future<?> reconcile(String project) {
		markPending(project);
		return worker.submit(() -> reconcileNow(project, false));
	}

	private void markPending(String project) {
		store.find(project).ifPresent((p) -> {
			List<Bundle> all = new ArrayList<>(p.released().values());
			p.draft().ifPresent(all::add);
			for (Bundle b : all) {
				SlotState s = states.get(key(project, slot(b)));
				if (s == null || !b.chunkSignature().equals(s.signature()) || s.status() == Status.FAILED) {
					states.put(key(project, slot(b)), new SlotState(Status.PENDING, b.chunkSignature(), b.chunks().size(), 0,
							0, null, Instant.now()));
				}
			}
		});
	}

	void reconcileNow(String project, boolean startup) {
		BundleStore.Project p = store.find(project).orElse(null);
		Map<String, Bundle> wanted = new LinkedHashMap<>();
		if (p != null) {
			p.released().values().forEach((b) -> wanted.put(slot(b), b));
			p.draft().ifPresent((b) -> wanted.put(BundleStore.DRAFT, b));
		}
		// a slot the store no longer holds (a draft a release superseded) leaves the index
		Set<String> held = new HashSet<>();
		states.keySet().stream().filter((k) -> k.startsWith(project + "/")).forEach((k) -> held.add(k.substring(project.length() + 1)));
		held.add(BundleStore.DRAFT);
		for (String s : held) {
			if (!wanted.containsKey(s)) {
				safely(() -> index.deleteSlot(project, s));
				states.remove(key(project, s));
			}
		}
		for (Bundle b : wanted.values()) {
			SlotState s = states.get(key(project, slot(b)));
			if (s != null && s.status() == Status.READY && b.chunkSignature().equals(s.signature())) {
				continue;
			}
			indexSlot(b, startup);
		}
		safely(index::flush);
	}

	private void indexSlot(Bundle b, boolean startup) {
		String project = b.projectKey();
		String slot = slot(b);
		String sig = b.chunkSignature();
		List<Chunk> chunks = b.chunks();
		try {
			if (startup && index.persistent() && b.released() && index.count(project, slot) == chunks.size()) {
				states.put(key(project, slot), new SlotState(Status.READY, sig, chunks.size(), 0, chunks.size(), null, Instant.now()));
				return;
			}
			states.put(key(project, slot), new SlotState(Status.INDEXING, sig, chunks.size(), 0, 0, null, Instant.now()));
			Map<String, float[]> known = index.densesByHash(project, chunks.stream().map(Chunk::contentHash).toList());
			List<Chunk> missing = chunks.stream().filter((c) -> !known.containsKey(c.contentHash())).toList();
			List<float[]> fresh = embedder.documents(missing);
			for (int i = 0; i < missing.size(); i++) {
				known.put(missing.get(i).contentHash(), fresh.get(i));
			}
			index.deleteSlot(project, slot);
			List<VectorIndex.Point> batch = new ArrayList<>();
			for (Chunk c : chunks) {
				batch.add(new VectorIndex.Point(J.uuidOf(project + "/" + slot + "/" + c.id()), known.get(c.contentHash()),
						sparse.document(c.text()), payload(b, slot, c)));
				if (batch.size() == 128) {
					index.upsert(batch);
					batch = new ArrayList<>();
				}
			}
			index.upsert(batch);
			states.put(key(project, slot), new SlotState(Status.READY, sig, chunks.size(), missing.size(),
					chunks.size() - missing.size(), null, Instant.now()));
			log.info("indexed {} {}: {} chunks, {} embedded, {} reused", project, slot, chunks.size(), missing.size(),
					chunks.size() - missing.size());
		}
		catch (RuntimeException e) {
			log.error("indexing {} {} failed", project, slot, e);
			states.put(key(project, slot), new SlotState(Status.FAILED, sig, chunks.size(), 0, 0, e.getMessage(), Instant.now()));
		}
	}

	private static Map<String, Object> payload(Bundle b, String slot, Chunk c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("slot", slot);
		m.put("version", b.version());
		m.put("channel", b.channel());
		m.put("chunk_id", c.id());
		m.put("content_hash", c.contentHash());
		m.put("kind", c.kind());
		m.put("file", c.file());
		m.put("components", c.components());
		m.put("adrs", c.adrs());
		m.put("doc_language", J.str(c.meta(), "doc_language"));
		return m;
	}

	private static void safely(Runnable r) {
		try {
			r.run();
		}
		catch (RuntimeException e) {
			log.warn("{}", e.getMessage());
		}
	}

	@Override
	public void close() {
		worker.shutdownNow();
	}

}
