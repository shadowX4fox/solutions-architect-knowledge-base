package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.shadowx4fox.archindex.index.QdrantVectorIndex;
import io.github.shadowx4fox.archindex.index.VectorIndex;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;

/**
 * The contract against a real Qdrant, when one is given: {@code QDRANT_TEST_HOST=localhost QDRANT_TEST_PORT=6334 ./mvnw
 * test -Dtest=QdrantVectorIndexTest}. Each test uses a collection of its own and drops it.
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_TEST_HOST", matches = ".+")
class QdrantVectorIndexTest extends VectorIndexContract {

	private final String collection = "archindex-test-" + UUID.randomUUID();

	private static ArchIndexProperties.Qdrant cfg(String collection) {
		return new ArchIndexProperties.Qdrant(System.getenv("QDRANT_TEST_HOST"),
				Integer.parseInt(System.getenv().getOrDefault("QDRANT_TEST_PORT", "6334")), false, null, collection);
	}

	@Override
	VectorIndex open() {
		return new QdrantVectorIndex(cfg(collection), 1024);
	}

	@Override
	void drop() throws Exception {
		var cfg = cfg(collection);
		try (var client = new QdrantClient(QdrantGrpcClient.newBuilder(cfg.host(), cfg.port(), false).build())) {
			client.deleteCollectionAsync(collection).get();
			client.deleteCollectionAsync(collection + "-slots").get();
		}
	}

	@Test
	void aReaderWaitsForTheWriterToCreateTheCollection() throws Exception {
		String other = collection + "-reader";
		try (var index = new QdrantVectorIndex(cfg(other), 1024)) {
			assertThatThrownBy(index::verify).hasMessageContaining("the writer creates it");
			index.ensure();
			index.ensure();   // again, as a second instance starting at the same time: the collection is taken as it is
			index.verify();
			try (var otherModel = new QdrantVectorIndex(cfg(other), 768)) {
				assertThatThrownBy(otherModel::verify).hasMessageContaining("1024-dimension");
			}
		}
		finally {
			var cfg = cfg(other);
			try (var client = new QdrantClient(QdrantGrpcClient.newBuilder(cfg.host(), cfg.port(), false).build())) {
				client.deleteCollectionAsync(other).get();
				client.deleteCollectionAsync(other + "-slots").get();
			}
		}
	}

}
