package io.github.shadowx4fox.archindex.index;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

import io.github.shadowx4fox.archindex.ArchIndexProperties;
import io.github.shadowx4fox.archindex.bundle.Chunk;

/**
 * Spring AI's embedding model with the model's task prompts, which Ollama does not add. The default, Qwen3-Embedding,
 * puts an instruction on the query and nothing on documents; EmbeddingGemma takes {@code task: search result | query: }
 * and {@code title: {title} | text: }. Both prefixes are settings; {@code {title}} in the document prefix is the chunk's title.
 * The context window ({@code archindex.embedding.context}) goes on every Ollama request: Spring AI 2.0.1 sends no
 * {@code num_ctx} from {@code spring.ai.ollama.embedding.options.num-ctx}, and Ollama then cuts a long chunk at the
 * model's loaded window without saying so.
 */
public class Embedder {

	private final EmbeddingModel model;

	private final ArchIndexProperties.Embedding cfg;

	private final String modelName;

	/** @param modelName the Ollama model to name on each request: request options replace the configured ones whole. */
	public Embedder(EmbeddingModel model, ArchIndexProperties.Embedding cfg, String modelName) {
		this.model = model;
		this.cfg = cfg;
		this.modelName = modelName;
	}

	public float[] query(String text) {
		return embed(List.of(cfg.queryPrefix() + text)).get(0);
	}

	private List<float[]> embed(List<String> texts) {
		if (!(model instanceof OllamaEmbeddingModel) || cfg.context() <= 0) {
			return model.embed(texts);
		}
		EmbeddingOptions options = OllamaEmbeddingOptions.builder().model(modelName).numCtx(cfg.context()).truncate(true).build();
		return model.call(new EmbeddingRequest(texts, options)).getResults().stream().map(Embedding::getOutput).toList();
	}

	public List<float[]> documents(List<Chunk> chunks) {
		List<float[]> out = new ArrayList<>(chunks.size());
		int batch = Math.max(1, cfg.batchSize());
		for (int i = 0; i < chunks.size(); i += batch) {
			List<String> texts = chunks.subList(i, Math.min(chunks.size(), i + batch))
				.stream()
				.map(this::documentText)
				.toList();
			List<float[]> vs = embed(texts);
			for (float[] v : vs) {
				if (v.length != cfg.dimensions()) {
					throw new IllegalStateException("the embedding model returned " + v.length
							+ " dimensions; archindex.embedding.dimensions is " + cfg.dimensions());
				}
			}
			out.addAll(vs);
		}
		return out;
	}

	String documentText(Chunk c) {
		String title = c.title() == null || c.title().isBlank() ? "none" : c.title().replace('|', '/');
		return cfg.documentPrefix().replace("{title}", title) + c.text();
	}

}
