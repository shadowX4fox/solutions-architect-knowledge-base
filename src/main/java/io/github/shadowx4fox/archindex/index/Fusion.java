package io.github.shadowx4fox.archindex.index;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.shadowx4fox.archindex.index.VectorIndex.Candidates;
import io.github.shadowx4fox.archindex.index.VectorIndex.Hit;

/**
 * Weighted reciprocal rank fusion of an engine's two candidate lists — the one implementation, so every engine ranks
 * the same: a point scores {@code weight / (60 + rank + 1)} in each list it is in, the dense list weighing 1 and the
 * sparse list {@code sparseWeight}. Equal scores share a rank (the first of their run), since an engine orders ties its
 * own way; scores within a millionth of the run's first are equal, as Qdrant scores in float and the memory engine in
 * double. Equal fused scores fall to project, slot and chunk id.
 */
public final class Fusion {

	static final int K = 60;

	private static final double TIE = 1e-6;

	private Fusion() {
	}

	public static List<Hit> rrf(Candidates c, double sparseWeight, int limit) {
		Map<Ref, Double> fused = new HashMap<>();
		add(fused, c.dense(), 1);
		add(fused, c.sparse(), sparseWeight);
		return fused.entrySet()
			.stream()
			.sorted(Map.Entry.<Ref, Double>comparingByValue()
				.reversed()
				.thenComparing(Map.Entry.comparingByKey(
						Comparator.comparing(Ref::project).thenComparing(Ref::slot).thenComparing(Ref::chunkId))))
			.limit(limit)
			.map((e) -> new Hit(e.getKey().chunkId(), e.getKey().project(), e.getKey().slot(), e.getValue()))
			.toList();
	}

	private static void add(Map<Ref, Double> fused, List<Hit> list, double weight) {
		int rank = 0;
		double first = 0;
		for (int r = 0; r < list.size(); r++) {
			double score = list.get(r).score();
			if (r == 0 || Math.abs(score - first) > TIE * Math.abs(first)) {
				rank = r;
				first = score;
			}
			Hit h = list.get(r);
			fused.merge(new Ref(h.project(), h.slot(), h.chunkId()), weight / (K + rank + 1), Double::sum);
		}
	}

	private record Ref(String project, String slot, String chunkId) {
	}

}
