package io.github.shadowx4fox.archindex;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code archindex.*}. Secrets (the tokens, the Qdrant key) come from the environment, never a file.
 * {@code publicUrl} is the base the IDE reaches the server at (https://alia-mcp.internal): unset, no part lists a
 * {@code download_url}.
 */
@ConfigurationProperties("archindex")
public record ArchIndexProperties(Path dataDir, String ingestToken, String mcpToken, String publicUrl, long maxBundleBytes,
		Role role, Duration refreshInterval, Duration retainReplaced, Store store, Qdrant qdrant, File file,
		Embedding embedding, Search search) {

	/** Several instances share one data directory and one Qdrant collection: one writes, the others read. */
	public enum Role {

		/** Publishes, indexes and answers: the only instance, or the one writer among several. */
		ALL,
		/** Answers only: refuses a publish, never indexes, and reloads what the writer publishes. */
		READER

	}

	public enum Store {

		/** Qdrant over gRPC: dense + sparse named vectors, fused by RRF. */
		QDRANT,
		/** In process, brute force, kept in one file: a single machine without Qdrant. Re-embeds nothing at start. */
		FILE,
		/** In process, brute force: development and tests. Re-embeds every bundle at start. */
		MEMORY

	}

	public record Qdrant(String host, int port, boolean tls, String apiKey, String collection) {
	}

	/** {@code path} unset: {@code <data-dir>/index/vectors-<dimensions>.bin}, one file per model size. */
	public record File(Path path) {

		public File {
			if (path != null && path.toString().isBlank()) {
				path = null;
			}
		}

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
		if (role == null) {
			role = Role.ALL;
		}
		if (refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()) {
			refreshInterval = Duration.ofSeconds(5);
		}
		if (retainReplaced == null || retainReplaced.isNegative()) {
			retainReplaced = Duration.ofMinutes(10);
		}
		if (qdrant == null) {
			qdrant = new Qdrant("localhost", 6334, false, null, "architecture");
		}
		if (file == null) {
			file = new File(null);
		}
		if (embedding == null) {
			embedding = new Embedding(QWEN3_QUERY, "", 1024, 32, 8192);
		}
		if (search == null) {
			search = new Search(8, 25, 50, 2000, 60000, 0.3);
		}
	}

}
