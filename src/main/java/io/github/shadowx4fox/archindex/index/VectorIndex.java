package io.github.shadowx4fox.archindex.index;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.github.shadowx4fox.archindex.index.SparseEncoder.SparseVec;

/**
 * Where the chunk vectors live. A point belongs to a slot — a project and one of its released versions, or its
 * draft — and carries the chunk id, so a hit is resolved against the bundle on disk: the store keeps no text.
 */
public interface VectorIndex {

	record Point(String id, float[] dense, SparseVec sparse, Map<String, Object> payload) {
	}

	record Scope(String project, String slot) {
	}

	record Query(float[] dense, SparseVec sparse, List<Scope> scopes, List<String> kinds, String component, int limit,
			int candidates) {
	}

	record Hit(String chunkId, String project, String slot, double score) {
	}

	/** Creates the collection and its payload indexes when missing. */
	void ensure();

	/** Dense vectors already computed for these texts in this project, by content hash: an unchanged chunk is never re-embedded. */
	Map<String, float[]> densesByHash(String project, Collection<String> contentHashes);

	void upsert(List<Point> points);

	void deleteSlot(String project, String slot);

	long count(String project, String slot);

	/** Dense and sparse candidates under the scopes and filters, fused by reciprocal rank. */
	List<Hit> search(Query query);

	String describe();

}
