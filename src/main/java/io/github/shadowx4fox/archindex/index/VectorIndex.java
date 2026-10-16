package io.github.shadowx4fox.archindex.index;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
			int candidates, double sparseWeight) {
	}

	record Hit(String chunkId, String project, String slot, double score) {
	}

	/** Creates the collection and its payload indexes when missing. */
	void ensure();

	/** What a reader does instead of {@link #ensure()}: checks the store is there and usable, never creates it. */
	default void verify() {
		ensure();
	}

	/** Dense vectors already computed for these texts in this project, by content hash: an unchanged chunk is never re-embedded. */
	Map<String, float[]> densesByHash(String project, Collection<String> contentHashes);

	void upsert(List<Point> points);

	void deleteSlot(String project, String slot);

	/**
	 * Removes the slot's points whose chunk id is not in {@code keep}: called after the new chunk set is upserted, so a
	 * slot being re-indexed is never empty to another instance searching it.
	 */
	void deleteSlotExcept(String project, String slot, Collection<String> keep);

	long count(String project, String slot);

	/** Dense and sparse candidates under the scopes and filters, fused by weighted reciprocal rank (dense 1, sparse {@code sparseWeight}). */
	List<Hit> search(Query query);

	/**
	 * Whether the points outlive the process. At start the indexer takes a released slot a persistent store already
	 * holds in full as ready (a released version's chunks never change); a volatile store is re-embedded.
	 */
	default boolean persistent() {
		return false;
	}

	/**
	 * Whether other server instances see these points: a reader instance ({@code archindex.role=reader}) needs a store
	 * the writer fills, and only Qdrant is one.
	 */
	default boolean shared() {
		return false;
	}

	/**
	 * Records that a slot holds the chunk set {@code signature} in full, written by the indexer when it finishes the
	 * slot: every instance reads it to know the slot is ready, and a restart skips a slot already held.
	 */
	default void markSlot(String project, String slot, String signature, int chunks) {
	}

	default void unmarkSlot(String project, String slot) {
	}

	/** The chunk set a slot was last indexed with, as {@link #markSlot} recorded it. */
	default Optional<String> slotSignature(String project, String slot) {
		return Optional.empty();
	}

	/** Makes what was written durable; called when the indexer finishes a project. */
	default void flush() {
	}

	String describe();

}
