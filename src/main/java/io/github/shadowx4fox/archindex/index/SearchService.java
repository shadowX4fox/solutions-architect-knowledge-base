package io.github.shadowx4fox.archindex.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.shadowx4fox.archindex.ArchIndexProperties;
import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.Chunk;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * Ranked search over the chunks: the query embedded with the model's query prompt and encoded sparse, both searched
 * under the same scope (a project's version, or the latest released version of every project), fused by reciprocal
 * rank. Each hit returns where it comes from — file, lines, anchor, version — so the agent can cite it or open the
 * whole chunk.
 */
public class SearchService {

	private final Bundles bundles;

	private final VectorIndex index;

	private final Indexer indexer;

	private final Embedder embedder;

	private final SparseEncoder sparse;

	private final ArchIndexProperties.Search cfg;

	public SearchService(Bundles bundles, VectorIndex index, Indexer indexer, Embedder embedder, SparseEncoder sparse,
			ArchIndexProperties.Search cfg) {
		this.bundles = bundles;
		this.index = index;
		this.indexer = indexer;
		this.embedder = embedder;
		this.sparse = sparse;
		this.cfg = cfg;
	}

	public Map<String, Object> search(String query, String project, String version, List<String> kinds,
			String component, boolean includeDraft, Integer limit) {
		if (query == null || query.isBlank()) {
			throw new ToolException("the query is empty");
		}
		int k = Math.max(1, Math.min(limit == null ? cfg.defaultLimit() : limit, cfg.maxLimit()));
		List<Bundle> scope = new ArrayList<>();
		if (project != null && !project.isBlank()) {
			scope.add(bundles.bundle(project, version));
			if (includeDraft && (version == null || version.isBlank() || "latest".equals(version))) {
				bundles.store().find(project).flatMap(BundleStore.Project::draft).ifPresent(scope::add);
			}
		}
		else {
			for (BundleStore.Project p : bundles.store().projects().values()) {
				p.latest().ifPresent(scope::add);
				if (includeDraft) {
					p.draft().ifPresent(scope::add);
				}
			}
		}
		String slug = null;
		if (component != null && !component.isBlank()) {
			if (scope.isEmpty() || scope.stream().map(Bundle::projectKey).distinct().count() != 1) {
				throw new ToolException("a component filter needs one project");
			}
			slug = J.str(bundles.component(scope.get(0), component), "slug");
		}
		List<String> notes = new ArrayList<>();
		List<VectorIndex.Scope> ready = new ArrayList<>();
		for (Bundle b : scope) {
			String slot = Indexer.slot(b);
			Indexer.SlotState s = indexer.state(b.projectKey(), slot);
			// ready for this chunk set: a slot indexed for the bundle a publish just replaced is not
			if (s != null && s.status() == Indexer.Status.READY && b.chunkSignature().equals(s.signature())) {
				ready.add(new VectorIndex.Scope(b.projectKey(), slot));
			}
			else {
				notes.add(b.projectKey() + " " + slot + " is not searchable yet ("
						+ (s == null ? "not indexed" : s.status() == Indexer.Status.READY ? "pending" : s.status().name().toLowerCase())
						+ (s != null && s.error() != null ? ": " + s.error() : "")
						+ ") — the ledger and build-pack tools answer for it now");
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("query", query);
		List<Map<String, Object>> hits = new ArrayList<>();
		if (!ready.isEmpty()) {
			List<VectorIndex.Hit> raw = index.search(new VectorIndex.Query(embedder.query(query), sparse.query(query), ready,
					kinds, slug, k, Math.max(cfg.candidates(), k), cfg.sparseWeight()));
			for (VectorIndex.Hit h : raw) {
				Bundle b = bundles.store().resolve(h.project(), h.slot()).orElse(null);
				Chunk c = b == null ? null : b.chunk(h.chunkId()).orElse(null);
				if (c != null) {
					hits.add(hit(b, c, h.score(), cfg.maxTextChars()));
				}
			}
		}
		out.put("hits", hits);
		if (!notes.isEmpty()) {
			out.put("notes", notes);
		}
		return out;
	}

	public static Map<String, Object> hit(Bundle b, Chunk c, Double score, int maxChars) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("version", b.version());
		m.put("channel", b.channel());
		m.put("chunk_id", c.id());
		if (score != null) {
			m.put("score", Math.round(score * 10000) / 10000.0);
		}
		m.put("kind", c.kind());
		m.put("title", c.title());
		m.put("anchor", J.str(c.meta(), "anchor"));
		m.put("file", c.file());
		m.put("lines", c.lines());
		m.put("components", c.components());
		m.put("adrs", c.adrs());
		String text = c.text();
		boolean cut = maxChars > 0 && text.length() > maxChars;
		m.put("text", cut ? text.substring(0, maxChars) : text);
		if (cut) {
			m.put("truncated", "first " + maxChars + " of " + text.length() + " characters — get_chunk returns the whole chunk");
		}
		return m;
	}

}
