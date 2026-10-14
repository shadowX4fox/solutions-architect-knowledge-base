package io.github.shadowx4fox.archindex;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code archindex.*}. Secrets (the tokens, the Qdrant key) come from the environment, never a file.
 */
@ConfigurationProperties("archindex")
public record ArchIndexProperties(Path dataDir, String ingestToken, String mcpToken, long maxBundleBytes,
		Store store, Qdrant qdrant, Embedding embedding, Search search) {

	public enum Store {

		/** Qdrant over gRPC: dense + sparse named vectors, fused by RRF. */
		QDRANT,
		/** In process, brute force: development and tests. Re-embeds every bundle at start. */
		MEMORY

	}

	public record Qdrant(String host, int port, boolean tls, String apiKey, String collection) {
	}

	/**
	 * The embedding model is Spring AI's (Ollama by default). The prefixes are the model's task prompts, added by this
	 * server: Ollama's {@code /api/embed} adds none.
	 */
	public record Embedding(String queryPrefix, String documentPrefix, int dimensions, int batchSize, int context) {
	}

	/**
	 * {@code sparseWeight} is the sparse list's weight in the reciprocal rank fusion (the dense list weighs 1): at 1 the
	 * exact-term list costs ordinary questions their best hit; 0.3–0.5 keeps both (eval/README.md).
	 */
	public record Search(int defaultLimit, int maxLimit, int candidates, int maxTextChars, int maxPartChars,
			double sparseWeight) {
	}

	/** Qwen3-Embedding's instruction format: the query carries the task, documents carry none. */
	public static final String QWEN3_QUERY = "Instruct: Given a question about a software architecture, retrieve the passages that answer it\nQuery: ";

	public ArchIndexProperties {
		if (dataDir == null) {
			dataDir = Path.of("data");
		}
		if (maxBundleBytes <= 0) {
			maxBundleBytes = 256L * 1024 * 1024;
		}
		if (store == null) {
			store = Store.QDRANT;
		}
		if (qdrant == null) {
			qdrant = new Qdrant("localhost", 6334, false, null, "architecture");
		}
		if (embedding == null) {
			embedding = new Embedding(QWEN3_QUERY, "", 1024, 32, 8192);
		}
		if (search == null) {
			search = new Search(8, 25, 50, 2000, 60000, 0.3);
		}
	}

}
