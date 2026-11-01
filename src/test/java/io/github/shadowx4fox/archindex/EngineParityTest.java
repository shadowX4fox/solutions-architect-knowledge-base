package io.github.shadowx4fox.archindex;

import static io.qdrant.client.ConditionFactory.filter;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.index.Embedder;
import io.github.shadowx4fox.archindex.index.Indexer;
import io.github.shadowx4fox.archindex.index.MemoryVectorIndex;
import io.github.shadowx4fox.archindex.index.QdrantVectorIndex;
import io.github.shadowx4fox.archindex.index.SparseEncoder;
import io.github.shadowx4fox.archindex.index.VectorIndex;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.IdfParams;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchParams;

/**
 * Whether Qdrant ranks as the memory engine (#38), when a Qdrant is given:
 * {@code QDRANT_TEST_HOST=localhost QDRANT_TEST_PORT=6334 ./mvnw test -Dtest=EngineParityTest}. The same points go to
 * both engines — the Order Desk draft and 1.4.0, and a project of noise that shares their words, so the collection's
 * document frequencies differ from a scope's — and the same questions are asked of both. Three rankings are compared:
 * the memory engine's, Qdrant's as the server searches it today (prefetches fused on the server), and Qdrant's two
 * candidate lists fused as the memory engine fuses them, with the inverse document frequency taken over the scope.
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_TEST_HOST", matches = ".+")
class EngineParityTest {

	private static final List<String> QUESTIONS = List.of("How does the web app talk to the hub API?",
			"Where are orders stored?", "What does ADR-012 decide?", "mTLS between pods", "idempotency of order booking",
			"Phase 2 book order", "What problem does Order Desk solve?", "What technical debt is open?", "TDR-002",
			"ledger-store", "integration I-01", "service mesh decision", "accept order phase", "Which components are there?");

	private static final int LIMIT = 5;

	private static final int CANDIDATES = 50;

	private static final double SPARSE_WEIGHT = 0.3;

	@TempDir
	Path tmp;

	@Test
	void qdrantRanksAsTheMemoryEngine() throws Exception {
		String collection = "archindex-parity-" + UUID.randomUUID();
		var cfg = new ArchIndexProperties.Qdrant(System.getenv("QDRANT_TEST_HOST"),
				Integer.parseInt(System.getenv().getOrDefault("QDRANT_TEST_PORT", "6334")), false, null, collection);
		var ecfg = new ArchIndexProperties.Embedding("q: ", "", 1024, 32, 0);
		Embedder embedder = new Embedder(new Fixtures.HashingEmbeddingModel(), ecfg, "stand-in");
		SparseEncoder sparse = new SparseEncoder();
		BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
		s.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("1.4.0"))));
		s.publish("order-desk", "1.4.0", "draft", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("draft"))));
		Bundle released = s.resolve("order-desk", "1.4.0").orElseThrow();
		Bundle draft = s.resolve("order-desk", "draft").orElseThrow();
		Lists memory = new Lists();
		try (var qdrant = new QdrantVectorIndex(cfg, 1024);
				var client = new QdrantClient(QdrantGrpcClient.newBuilder(cfg.host(), cfg.port(), false).build())) {
			qdrant.ensure();
			for (VectorIndex index : List.of(memory, qdrant)) {
				new Indexer(s, index, embedder, sparse, false).reconcileAll().get();
				index.upsert(noise(sparse));
			}
			List<List<VectorIndex.Scope>> scopes = List.of(
					List.of(new VectorIndex.Scope("order-desk", Indexer.slot(draft))),
					List.of(new VectorIndex.Scope("order-desk", Indexer.slot(released))),
					List.of(new VectorIndex.Scope("order-desk", Indexer.slot(draft)), new VectorIndex.Scope("noise", "1.0.0")));
			int asked = 0;
			int sameToday = 0;
			int sameFused = 0;
			List<String> report = new ArrayList<>();
			for (List<VectorIndex.Scope> scope : scopes) {
				for (String question : QUESTIONS) {
					var q = new VectorIndex.Query(embedder.query(question), sparse.query(question), scope, null, null, LIMIT,
							CANDIDATES, SPARSE_WEIGHT);
					List<Cand> m = fuse(memory.lists(q), q);
					List<Cand> fused = fuse(qdrantLists(client, collection, q), q);
					asked++;
					sameToday += ids(memory.search(q)).equals(ids(qdrant.search(q))) ? 1 : 0;
					sameFused += keys(m).equals(keys(fused)) ? 1 : 0;
					if (!keys(m).equals(keys(fused))) {
						report.add(scopeName(scope) + " · " + question + "\n    memory " + m + "\n    qdrant " + fused);
					}
				}
			}
			System.out.println("engine parity, top " + LIMIT + " in order: as searched today " + sameToday + "/" + asked
					+ ", candidate lists fused by one rule " + sameFused + "/" + asked);
			report.forEach(System.out::println);
			assertThat(sameFused).as("the two engines' candidate lists fused by one rule").isEqualTo(asked);
		}
		finally {
			try (var client = new QdrantClient(QdrantGrpcClient.newBuilder(cfg.host(), cfg.port(), false).build())) {
				client.deleteCollectionAsync(collection).get();
				client.deleteCollectionAsync(collection + "-slots").get();
			}
		}
	}

	/** Forty points of another project made of Order Desk's words: the collection's frequencies are no longer the scope's. */
	private static List<VectorIndex.Point> noise(SparseEncoder sparse) {
		String[] words = { "order", "hub", "api", "web", "app", "ledger", "store", "phase", "book", "accept", "desk",
				"integration", "component", "adr", "debt" };
		List<VectorIndex.Point> out = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			String text = "noise " + i + " " + words[i % words.length] + " " + words[(i * 7 + 3) % words.length] + " "
					+ words[(i * 11 + 5) % words.length];
			out.add(new VectorIndex.Point(UUID.nameUUIDFromBytes(("noise/" + i).getBytes()).toString(),
					Fixtures.HashingEmbeddingModel.vector(text), sparse.document(text),
					Map.of("project", "noise", "slot", "1.0.0", "chunk_id", "noise-" + i, "kind", "doc", "components",
							List.of(), "content_hash", "noise-" + i)));
		}
		return out;
	}

	/** One candidate: the point (project/slot/chunk) and the engine's score in its list. */
	record Cand(String key, double score) {

		@Override
		public String toString() {
			return key.substring(key.lastIndexOf('/') + 1, Math.min(key.length(), key.lastIndexOf('/') + 9))
					+ String.format(" %.6f", score);
		}

	}

	/**
	 * Weighted reciprocal rank fusion (k 60, dense 1, sparse {@code sparseWeight}) where equal scores share a rank: an
	 * engine's order among ties is its own, so the rank of a tie must not depend on it. Scores within a millionth of the
	 * first of their run are equal — Qdrant scores in float, the memory engine in double. Ties in the fused score fall
	 * to the key.
	 */
	static List<Cand> fuse(List<List<Cand>> lists, VectorIndex.Query q) {
		Map<String, Double> fused = new HashMap<>();
		double[] weights = { 1, q.sparseWeight() };
		for (int l = 0; l < 2; l++) {
			List<Cand> list = lists.get(l);
			int rank = 0;
			double first = Double.NaN;
			for (int r = 0; r < list.size(); r++) {
				double sc = list.get(r).score();
				if (r == 0 || Math.abs(sc - first) > 1e-6 * Math.abs(first)) {
					rank = r;
					first = sc;
				}
				fused.merge(list.get(r).key(), weights[l] / (60 + rank + 1), Double::sum);
			}
		}
		return fused.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(q.limit())
			.map((e) -> new Cand(e.getKey(), e.getValue()))
			.toList();
	}

	/** Qdrant's two candidate lists in one batch, under the same filter, the IDF taken over the scope (#38's design). */
	private static List<List<Cand>> qdrantLists(QdrantClient client, String collection, VectorIndex.Query q) throws Exception {
		Filter.Builder any = Filter.newBuilder();
		for (VectorIndex.Scope sc : q.scopes()) {
			any.addShould(filter(Filter.newBuilder()
				.addMust(matchKeyword("project", sc.project()))
				.addMust(matchKeyword("slot", sc.slot()))
				.build()));
		}
		Filter f = Filter.newBuilder().addMust(filter(any.build())).build();
		var payload = WithPayloadSelectorFactory.include(List.of("chunk_id", "project", "slot"));
		QueryPoints dense = QueryPoints.newBuilder()
			.setCollectionName(collection)
			.setQuery(QueryFactory.nearest(q.dense()))
			.setUsing("dense")
			.setFilter(f)
			.setLimit(q.candidates())
			.setWithPayload(payload)
			.build();
		QueryPoints sparse = QueryPoints.newBuilder()
			.setCollectionName(collection)
			.setQuery(QueryFactory.nearest(boxed(q.sparse().values()), boxed(q.sparse().indices())))
			.setUsing("sparse")
			.setFilter(f)
			.setParams(SearchParams.newBuilder().setIdf(IdfParams.newBuilder().setCorpus(f)))
			.setLimit(q.candidates())
			.setWithPayload(payload)
			.build();
		List<List<Cand>> out = new ArrayList<>();
		for (var r : client.queryBatchAsync(collection, List.of(dense, sparse)).get()) {
			out.add(r.getResultList().stream().filter((p) -> p.getScore() > 0).map((p) -> new Cand(key(p), p.getScore())).toList());
		}
		return out;
	}

	/** The memory engine's two candidate lists, as its search ranks them before it fuses. */
	static class Lists extends MemoryVectorIndex {

		List<List<Cand>> lists(VectorIndex.Query q) {
			List<Point> pool = points.values()
				.stream()
				.filter((p) -> q.scopes()
					.stream()
					.anyMatch((s) -> s.project().equals(p.payload().get("project")) && s.slot().equals(p.payload().get("slot"))))
				.toList();
			Map<Integer, Integer> df = new HashMap<>();
			pool.forEach((p) -> {
				for (int i : p.sparse().indices()) {
					df.merge(i, 1, Integer::sum);
				}
			});
			List<Cand> dense = pool.stream()
				.map((p) -> new Cand(key(p), cosine(q.dense(), p.dense())))
				.sorted(Comparator.comparingDouble((Cand h) -> -h.score()))
				.limit(q.candidates())
				.toList();
			List<Cand> sparse = pool.stream()
				.map((p) -> new Cand(key(p), sparseScore(q.sparse(), p.sparse(), df, pool.size())))
				.filter((h) -> h.score() > 0)
				.sorted(Comparator.comparingDouble((Cand h) -> -h.score()))
				.limit(q.candidates())
				.toList();
			return List.of(dense, sparse);
		}

		private static String key(Point p) {
			return p.payload().get("project") + "/" + p.payload().get("slot") + "/" + p.payload().get("chunk_id");
		}

		private static double cosine(float[] a, float[] b) {
			double dot = 0;
			double na = 0;
			double nb = 0;
			for (int i = 0; i < Math.min(a.length, b.length); i++) {
				dot += a[i] * b[i];
				na += a[i] * a[i];
				nb += b[i] * b[i];
			}
			return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
		}

		private static double sparseScore(SparseEncoder.SparseVec q, SparseEncoder.SparseVec d, Map<Integer, Integer> df,
				int n) {
			Map<Integer, Float> dm = new HashMap<>();
			for (int i = 0; i < d.indices().length; i++) {
				dm.put(d.indices()[i], d.values()[i]);
			}
			double s = 0;
			for (int i = 0; i < q.indices().length; i++) {
				Float w = dm.get(q.indices()[i]);
				if (w != null) {
					int f = df.getOrDefault(q.indices()[i], 0);
					s += q.values()[i] * w * Math.log(1 + (n - f + 0.5) / (f + 0.5));
				}
			}
			return s;
		}

	}

	private static List<String> keys(List<Cand> hits) {
		return hits.stream().map(Cand::key).toList();
	}

	private static String key(ScoredPoint p) {
		return p.getPayloadMap().get("project").getStringValue() + "/" + p.getPayloadMap().get("slot").getStringValue() + "/"
				+ p.getPayloadMap().get("chunk_id").getStringValue();
	}

	private static List<String> ids(List<VectorIndex.Hit> hits) {
		return hits.stream().map((h) -> h.project() + "/" + h.slot() + "/" + h.chunkId()).toList();
	}

	private static String scopeName(List<VectorIndex.Scope> scope) {
		return String.join("+", scope.stream().map((s) -> s.project() + "/" + s.slot()).toList());
	}

	private static List<Float> boxed(float[] a) {
		List<Float> l = new ArrayList<>(a.length);
		for (float x : a) {
			l.add(x);
		}
		return l;
	}

	private static List<Integer> boxed(int[] a) {
		List<Integer> l = new ArrayList<>(a.length);
		for (int x : a) {
			l.add(x);
		}
		return l;
	}

}
