package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.index.Embedder;
import io.github.shadowx4fox.archindex.index.Indexer;
import io.github.shadowx4fox.archindex.index.QdrantVectorIndex;
import io.github.shadowx4fox.archindex.index.SparseEncoder;
import io.github.shadowx4fox.archindex.index.VectorIndex;

/**
 * The indexer against a real Qdrant, when one is given: {@code QDRANT_TEST_HOST=localhost QDRANT_TEST_PORT=6334 ./mvnw test}.
 * Each run uses a collection of its own and drops it.
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_TEST_HOST", matches = ".+")
class QdrantTest {

	@TempDir
	Path tmp;

	@Test
	void aSlotBeingReindexedIsNeverEmptyAndKeepsOnlyItsChunks() throws Exception {
		String collection = "archindex-test-" + UUID.randomUUID();
		var cfg = new ArchIndexProperties.Qdrant(System.getenv("QDRANT_TEST_HOST"),
				Integer.parseInt(System.getenv().getOrDefault("QDRANT_TEST_PORT", "6334")), false, null, collection);
		List<Long> seen = new ArrayList<>();
		try (var index = new QdrantVectorIndex(cfg, 1024) {
			@Override
			public void upsert(List<VectorIndex.Point> batch) {
				seen.add(count("order-desk", "draft"));
				super.upsert(batch);
			}
		}) {
			// a reader never creates the collection: it waits for the writer
			org.assertj.core.api.Assertions.assertThatThrownBy(index::verify).hasMessageContaining("the writer creates it");
			index.ensure();
			index.ensure();   // again, as a second instance starting at the same time: the collection is taken as it is
			index.verify();
			BundleStore s = new BundleStore(tmp.resolve("data"), 50_000_000);
			s.publish("order-desk", "1.4.0", "draft", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("draft"))));
			reconcile(s, index);
			assertThat(index.count("order-desk", "draft")).isEqualTo(23);
			index.upsert(List.of(new VectorIndex.Point(UUID.randomUUID().toString(), Fixtures.HashingEmbeddingModel.vector("gone"),
					new SparseEncoder().document("gone"), Map.of("project", "order-desk", "slot", "draft", "chunk_id", "gone"))));
			assertThat(index.count("order-desk", "draft")).isEqualTo(24);
			seen.clear();
			index.unmarkSlot("order-desk", "draft");   // as before marks: the draft is indexed again
			reconcile(s, index);
			assertThat(seen).isNotEmpty().allSatisfy((n) -> assertThat(n).isGreaterThanOrEqualTo(23));
			assertThat(index.count("order-desk", "draft")).isEqualTo(23);
			// the writer marked the slot with its chunk set: a restart writes nothing, a reader takes it as ready
			String sig = s.resolve("order-desk", "draft").orElseThrow().chunkSignature();
			assertThat(index.slotSignature("order-desk", "draft")).contains(sig);
			seen.clear();
			assertThat(reconcile(s, index, false).state("order-desk", "draft").status()).isEqualTo(Indexer.Status.READY);
			assertThat(seen).isEmpty();
			assertThat(reconcile(s, index, true).state("order-desk", "draft").status()).isEqualTo(Indexer.Status.READY);
			index.unmarkSlot("order-desk", "draft");
			assertThat(reconcile(s, index, true).state("order-desk", "draft").status()).isEqualTo(Indexer.Status.PENDING);
			assertThat(seen).isEmpty();   // a reader never writes
		}
		finally {
			try (var client = new io.qdrant.client.QdrantClient(
					io.qdrant.client.QdrantGrpcClient.newBuilder(cfg.host(), cfg.port(), false).build())) {
				client.deleteCollectionAsync(collection).get();
				client.deleteCollectionAsync(collection + "-slots").get();
			}
		}
	}

	private static void reconcile(BundleStore s, VectorIndex index) throws Exception {
		reconcile(s, index, false);
	}

	private static Indexer reconcile(BundleStore s, VectorIndex index, boolean follower) throws Exception {
		var cfg = new ArchIndexProperties.Embedding("q: ", "", 1024, 32, 0);
		Indexer i = new Indexer(s, index, new Embedder(new Fixtures.HashingEmbeddingModel(), cfg, "stand-in"),
				new SparseEncoder(), follower);
		i.reconcileAll().get();
		return i;
	}

}
