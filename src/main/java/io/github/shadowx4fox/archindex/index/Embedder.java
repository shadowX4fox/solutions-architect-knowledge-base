package io.github.shadowx4fox.archindex.index;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;

import io.github.shadowx4fox.archindex.ArchIndexProperties;
import io.github.shadowx4fox.archindex.bundle.Chunk;

/**
 * Spring AI's embedding model with the task prompts EmbeddingGemma expects: a document is embedded as
 * {@code title: <title> | text: <text>}, a query as {@code task: search result | query: <query>}. Both prefixes are
 * settings; {@code {title}} in the document prefix is the chunk's title.
 */
public class Embedder {

	private final EmbeddingModel model;

	private final ArchIndexProperties.Embedding cfg;

	public Embedder(EmbeddingModel model, ArchIndexProperties.Embedding cfg) {
		this.model = model;
		this.cfg = cfg;
	}

	public float[] query(String text) {
		return model.embed(cfg.queryPrefix() + text);
	}

	public List<float[]> documents(List<Chunk> chunks) {
		List<float[]> out = new ArrayList<>(chunks.size());
		int batch = Math.max(1, cfg.batchSize());
		for (int i = 0; i < chunks.size(); i += batch) {
			List<String> texts = chunks.subList(i, Math.min(chunks.size(), i + batch))
				.stream()
				.map(this::documentText)
				.toList();
			List<float[]> vs = model.embed(texts);
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
