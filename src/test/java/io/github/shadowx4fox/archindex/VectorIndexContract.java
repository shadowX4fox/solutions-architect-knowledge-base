package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.index.Embedder;
import io.github.shadowx4fox.archindex.index.Fusion;
import io.github.shadowx4fox.archindex.index.Indexer;
import io.github.shadowx4fox.archindex.index.MemoryVectorIndex;
import io.github.shadowx4fox.archindex.index.SparseEncoder;
import io.github.shadowx4fox.archindex.index.VectorIndex;
import io.github.shadowx4fox.archindex.index.VectorIndex.Hit;
import io.github.shadowx4fox.archindex.index.VectorIndex.Query;
import io.github.shadowx4fox.archindex.index.VectorIndex.Scope;

/**
 * What every {@link VectorIndex} engine does, run against each one: its points by slot, its filters, the vectors it
 * reuses, its slot marks, a re-index that never empties a slot, and the ranking — an engine's candidate lists, fused
 * by {@link Fusion}, rank as the memory engine's. A new engine is a subclass naming how to open it.
 */
abstract class VectorIndexContract {

	private static final List<String> QUESTIONS = List.of("How does the web app talk to the hub API?",
			"Where are orders stored?", "What does ADR-012 decide?", "mTLS between pods", "idempotency of order booking",
			"Phase 2 book order", "What problem does Order Desk solve?", "What technical debt is open?", "TDR-002",
			"ledger-store", "integration I-01", "service mesh decision", "accept order phase", "Which components are there?",
			"Which component writes the ledger for an order?", "ledger of the hub API", "book the order in the ledger");

	private static final SparseEncoder SPARSE = new SparseEncoder();

	@TempDir
	Path tmp;

	VectorIndex index;

	/** A new, empty index of 1,024 dimensions, not yet ensured. */
	abstract VectorIndex open() throws Exception;

	/** Removes what {@link #open()} made outside the temporary directory. */
	void drop() throws Exception {
	}

	@BeforeEach
	void ensure() throws Exception {
		index = open();
		index.ensure();
	}

	@AfterEach
	void close() throws Exception {
		try {
			if (index instanceof AutoCloseable c) {
				c.close();
			}
		}
		finally {
			drop();
		}
	}

	@Test
	void pointsAreCountedAndDeletedBySlot() {
		index.upsert(List.of(point("order-desk", "1.3.0", "a", "order", "doc", List.of()),
				point("order-desk", "1.3.0", "b", "ledger", "doc", List.of()),
				point("order-desk", "draft", "a", "order", "doc", List.of()),
				point("other", "1.3.0", "a", "order", "doc", List.of())));
		assertThat(index.count("order-desk", "1.3.0")).isEqualTo(2);
		assertThat(index.count("order-desk", "draft")).isEqualTo(1);
		index.deleteSlotExcept("order-desk", "1.3.0", List.of("b"));
		assertThat(index.count("order-desk", "1.3.0")).isEqualTo(1);
		index.deleteSlot("order-desk", "draft");
		assertThat(index.count("order-desk", "draft")).isZero();
		assertThat(index.count("order-desk", "1.3.0")).isEqualTo(1);
		assertThat(index.count("other", "1.3.0")).isEqualTo(1);
	}

	@Test
	void bothListsKeepToTheScopesKindsAndComponent() {
		index.upsert(List.of(point("order-desk", "1.3.0", "adr", "order desk ledger", "adr", List.of("hub-api")),
				point("order-desk", "1.3.0", "doc", "order desk ledger", "doc", List.of("web-app")),
				point("order-desk", "draft", "doc", "order desk ledger", "doc", List.of("hub-api")),
				point("other", "1.3.0", "doc", "order desk ledger", "doc", List.of("hub-api"))));
		Scope released = new Scope("order-desk", "1.3.0");
		Scope draft = new Scope("order-desk", "draft");
		assertThat(found(query("order ledger", List.of(released), null, null))).containsOnly("order-desk/1.3.0/adr",
				"order-desk/1.3.0/doc");
		assertThat(found(query("order ledger", List.of(released, draft), null, null))).containsOnly("order-desk/1.3.0/adr",
				"order-desk/1.3.0/doc", "order-desk/draft/doc");
		assertThat(found(query("order ledger", List.of(released, draft), List.of("adr"), null)))
			.containsOnly("order-desk/1.3.0/adr");
		assertThat(found(query("order ledger", List.of(released, draft), null, "hub-api")))
			.containsOnly("order-desk/1.3.0/adr", "order-desk/draft/doc");
		// a word no point holds: dense still ranks every point in scope, sparse finds none
		VectorIndex.Candidates none = index.candidates(query("kubernetes", List.of(released), null, null));
		assertThat(none.dense()).hasSize(2);
		assertThat(none.sparse()).isEmpty();
	}

	@Test
	void denseVectorsAreFoundByContentHashInTheProject() {
		index.upsert(List.of(point("order-desk", "1.3.0", "a", "order", "doc", List.of()),
				point("other", "1.3.0", "b", "ledger", "doc", List.of())));
		Map<String, float[]> held = index.densesByHash("order-desk", List.of("hash-a", "hash-b", "hash-missing"));
		assertThat(held).containsOnlyKeys("hash-a");
		assertThat(held.get("hash-a")).hasSize(1024);
	}

	@Test
	void aSlotMarkIsReadBackAndRemoved() {
		index.markSlot("order-desk", "1.3.0", "sig-1", 23);
		assertThat(index.slotSignature("order-desk", "1.3.0")).contains("sig-1");
		assertThat(index.slotSignature("order-desk", "draft")).isEmpty();
		index.unmarkSlot("order-desk", "1.3.0");
		assertThat(index.slotSignature("order-desk", "1.3.0")).isEmpty();
	}

	@Test
	void aSlotBeingReindexedIsNeverEmptyAndKeepsOnlyItsChunks() throws Exception {
		List<Long> seen = new ArrayList<>();
		VectorIndex watched = new Watched(index, () -> seen.add(index.count("order-desk", "draft")));
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		s.publish("order-desk", "1.4.0", "draft", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("draft"))));
		reconcile(s, watched, false);
		// the fixture's own chunk count: it moves when the fixtures are regenerated
		long chunks = s.resolve("order-desk", "draft").orElseThrow().chunks().size();
		assertThat(index.count("order-desk", "draft")).isEqualTo(chunks);
		index.upsert(List.of(point("order-desk", "draft", "gone", "gone", "doc", List.of())));
		assertThat(index.count("order-desk", "draft")).isEqualTo(chunks + 1);
		seen.clear();
		index.unmarkSlot("order-desk", "draft");   // as before marks: the draft is indexed again
		reconcile(s, watched, false);
		assertThat(seen).isNotEmpty().allSatisfy((n) -> assertThat(n).isGreaterThanOrEqualTo(chunks));
		assertThat(index.count("order-desk", "draft")).isEqualTo(chunks);
		// the writer marked the slot with its chunk set: a restart writes nothing, a reader takes it as ready
		String sig = s.resolve("order-desk", "draft").orElseThrow().chunkSignature();
		assertThat(index.slotSignature("order-desk", "draft")).contains(sig);
		seen.clear();
		assertThat(reconcile(s, watched, false).state("order-desk", "draft").status()).isEqualTo(Indexer.Status.READY);
		assertThat(seen).isEmpty();
		assertThat(reconcile(s, watched, true).state("order-desk", "draft").status()).isEqualTo(Indexer.Status.READY);
		index.unmarkSlot("order-desk", "draft");
		assertThat(reconcile(s, watched, true).state("order-desk", "draft").status()).isEqualTo(Indexer.Status.PENDING);
		assertThat(seen).isEmpty();   // a reader never writes
	}

	/**
	 * The same points in this engine and the memory engine — the Order Desk 1.4.0 and draft, and a project of noise
	 * made of their words, so the collection's document frequencies are not a scope's — and the same questions asked
	 * of both, in three scopes: each list ranks every point as the memory engine's does (equal scores sharing a rank),
	 * and the top five, fused, are the same points in the same order.
	 */
	@Test
	void itRanksAsTheMemoryEngine() throws Exception {
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		s.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("1.4.0"))));
		s.publish("order-desk", "1.4.0", "draft", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("draft"))));
		Bundle released = s.resolve("order-desk", "1.4.0").orElseThrow();
		Bundle draft = s.resolve("order-desk", "draft").orElseThrow();
		MemoryVectorIndex memory = new MemoryVectorIndex();
		Embedder embedder = embedder();
		for (VectorIndex i : List.of(memory, index)) {
			reconcile(s, i, false);
			i.upsert(noise());
		}
		List<List<Scope>> scopes = List.of(List.of(new Scope("order-desk", Indexer.slot(draft))),
				List.of(new Scope("order-desk", Indexer.slot(released))),
				List.of(new Scope("order-desk", Indexer.slot(draft)), new Scope("noise", "1.0.0")));
		List<String> differ = new ArrayList<>();
		for (List<Scope> scope : scopes) {
			for (String question : QUESTIONS) {
				Query q = new Query(embedder.query(question), SPARSE.query(question), scope, null, null, 50);
				VectorIndex.Candidates mine = index.candidates(q);
				VectorIndex.Candidates theirs = memory.candidates(q);
				List<String> expected = keys(Fusion.rrf(theirs, 0.3, 5));
				List<String> actual = keys(Fusion.rrf(mine, 0.3, 5));
				if (!actual.equals(expected)) {
					differ.add(scope + " · " + question + ": " + actual + " — memory " + expected);
				}
				if (!ranks(mine.dense(), q.candidates()).equals(ranks(theirs.dense(), q.candidates()))) {
					differ.add(scope + " · " + question + ": the dense list");
				}
				if (!ranks(mine.sparse(), q.candidates()).equals(ranks(theirs.sparse(), q.candidates()))) {
					differ.add(scope + " · " + question + ": the sparse list");
				}
			}
		}
		assertThat(differ).as("questions ranked otherwise than by the memory engine").isEmpty();
	}

	static Indexer reconcile(BundleStore s, VectorIndex index, boolean follower) throws Exception {
		Indexer i = new Indexer(s, index, embedder(), SPARSE, follower);
		i.reconcileAll().get();
		return i;
	}

	private static Embedder embedder() {
		return new Embedder(new Fixtures.HashingEmbeddingModel(), new ArchIndexProperties.Embedding("q: ", "", 1024, 32, 0),
				"stand-in");
	}

	private static VectorIndex.Point point(String project, String slot, String chunk, String text, String kind,
			List<String> components) {
		return new VectorIndex.Point(UUID.nameUUIDFromBytes((project + "/" + slot + "/" + chunk).getBytes()).toString(),
				Fixtures.HashingEmbeddingModel.vector(text), SPARSE.document(text), Map.of("project", project, "slot", slot,
						"chunk_id", chunk, "kind", kind, "components", components, "content_hash", "hash-" + chunk));
	}

	/**
	 * Forty points of another project made of Order Desk's words, every one with "ledger": common in the collection,
	 * rare in Order Desk, so a sparse list weighted over the collection ranks a question on the ledger otherwise.
	 */
	private static List<VectorIndex.Point> noise() {
		String[] words = { "order", "hub", "api", "web", "app", "store", "phase", "book", "accept", "desk", "integration",
				"component", "adr", "debt" };
		List<VectorIndex.Point> out = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			out.add(point("noise", "1.0.0", "noise-" + i, "noise " + i + " ledger " + words[i % words.length] + " "
					+ words[(i * 5 + 3) % words.length], "doc", List.of()));
		}
		return out;
	}

	private static Query query(String text, List<Scope> scopes, List<String> kinds, String component) {
		return new Query(Fixtures.HashingEmbeddingModel.vector(text), SPARSE.query(text), scopes, kinds, component, 50);
	}

	/** Every point either list returned. */
	private List<String> found(Query q) {
		VectorIndex.Candidates c = index.candidates(q);
		return Stream.concat(c.dense().stream(), c.sparse().stream()).map(VectorIndexContract::key).distinct().toList();
	}

	private static String key(Hit h) {
		return h.project() + "/" + h.slot() + "/" + h.chunkId();
	}

	/**
	 * Each point's rank in a list, equal scores (within a millionth) sharing the rank of the first of their run. In a
	 * list cut at {@code candidates}, the last run is left out: the cut may split it, and which of its points an engine
	 * keeps is its own order.
	 */
	private static Map<String, Integer> ranks(List<Hit> list, int candidates) {
		Map<String, Integer> out = new java.util.LinkedHashMap<>();
		int rank = 0;
		double first = 0;
		for (int r = 0; r < list.size(); r++) {
			double score = list.get(r).score();
			if (r == 0 || Math.abs(score - first) > 1e-6 * Math.abs(first)) {
				rank = r;
				first = score;
			}
			out.put(key(list.get(r)), rank);
		}
		if (list.size() >= candidates) {
			int last = rank;
			out.values().removeIf((v) -> v == last);
		}
		return out;
	}

	private static List<String> keys(List<Hit> hits) {
		return hits.stream().map(VectorIndexContract::key).toList();
	}

	/** An engine whose upserts are watched: what the slot holds when each batch arrives. */
	private record Watched(VectorIndex index, Runnable onUpsert) implements VectorIndex {

		@Override
		public void ensure() {
			index.ensure();
		}

		@Override
		public void verify() {
			index.verify();
		}

		@Override
		public Map<String, float[]> densesByHash(String project, Collection<String> contentHashes) {
			return index.densesByHash(project, contentHashes);
		}

		@Override
		public void upsert(List<Point> points) {
			onUpsert.run();
			index.upsert(points);
		}

		@Override
		public void deleteSlot(String project, String slot) {
			index.deleteSlot(project, slot);
		}

		@Override
		public void deleteSlotExcept(String project, String slot, Collection<String> keep) {
			index.deleteSlotExcept(project, slot, keep);
		}

		@Override
		public long count(String project, String slot) {
			return index.count(project, slot);
		}

		@Override
		public Candidates candidates(Query query) {
			return index.candidates(query);
		}

		@Override
		public boolean persistent() {
			return index.persistent();
		}

		@Override
		public boolean shared() {
			return index.shared();
		}

		@Override
		public void markSlot(String project, String slot, String signature, int chunks) {
			index.markSlot(project, slot, signature, chunks);
		}

		@Override
		public void unmarkSlot(String project, String slot) {
			index.unmarkSlot(project, slot);
		}

		@Override
		public Optional<String> slotSignature(String project, String slot) {
			return index.slotSignature(project, slot);
		}

		@Override
		public void flush() {
			index.flush();
		}

		@Override
		public String describe() {
			return index.describe();
		}

	}

}
