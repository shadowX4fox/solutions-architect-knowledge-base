package io.github.shadowx4fox.archindex.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.index.SparseEncoder.SparseVec;

/**
 * Brute force in process — development and tests. The same contract as Qdrant: cosine on the dense vector, a sparse
 * dot product weighted by inverse document frequency, reciprocal rank fusion of the two candidate lists.
 */
public class MemoryVectorIndex implements VectorIndex {

	private static final int RRF_K = 60;

	private final Map<String, Point> points = new ConcurrentHashMap<>();

	@Override
	public void ensure() {
	}

	@Override
	public Map<String, float[]> densesByHash(String project, Collection<String> contentHashes) {
		Set<String> want = new HashSet<>(contentHashes);
		Map<String, float[]> out = new HashMap<>();
		for (Point p : points.values()) {
			String h = J.str(p.payload(), "content_hash");
			if (project.equals(J.str(p.payload(), "project")) && want.contains(h)) {
				out.putIfAbsent(h, p.dense());
			}
		}
		return out;
	}

	@Override
	public void upsert(List<Point> batch) {
		batch.forEach((p) -> points.put(p.id(), p));
	}

	@Override
	public void deleteSlot(String project, String slot) {
		points.values().removeIf((p) -> inSlot(p, project, slot));
	}

	@Override
	public long count(String project, String slot) {
		return points.values().stream().filter((p) -> inSlot(p, project, slot)).count();
	}

	private static boolean inSlot(Point p, String project, String slot) {
		return project.equals(J.str(p.payload(), "project")) && slot.equals(J.str(p.payload(), "slot"));
	}

	@Override
	public List<Hit> search(Query q) {
		List<Point> pool = points.values().stream().filter((p) -> matches(p, q)).toList();
		Map<Integer, Integer> df = new HashMap<>();
		for (Point p : pool) {
			for (int i : p.sparse().indices()) {
				df.merge(i, 1, Integer::sum);
			}
		}
		int n = pool.size();
		List<Point> byDense = pool.stream()
			.sorted(Comparator.comparingDouble((Point p) -> -cosine(q.dense(), p.dense())))
			.limit(q.candidates())
			.toList();
		List<Map.Entry<Point, Double>> sparse = new ArrayList<>();
		for (Point p : pool) {
			double s = sparseScore(q.sparse(), p.sparse(), df, n);
			if (s > 0) {
				sparse.add(Map.entry(p, s));
			}
		}
		sparse.sort(Map.Entry.<Point, Double>comparingByValue().reversed());
		Map<String, Double> fused = new HashMap<>();
		for (int r = 0; r < byDense.size(); r++) {
			fused.merge(byDense.get(r).id(), 1.0 / (RRF_K + r + 1), Double::sum);
		}
		for (int r = 0; r < Math.min(sparse.size(), q.candidates()); r++) {
			fused.merge(sparse.get(r).getKey().id(), 1.0 / (RRF_K + r + 1), Double::sum);
		}
		return fused.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
			.limit(q.limit())
			.map((e) -> {
				Point p = points.get(e.getKey());
				return new Hit(J.str(p.payload(), "chunk_id"), J.str(p.payload(), "project"), J.str(p.payload(), "slot"),
						e.getValue());
			})
			.toList();
	}

	private static boolean matches(Point p, Query q) {
		Map<String, Object> pl = p.payload();
		boolean scoped = q.scopes()
			.stream()
			.anyMatch((s) -> s.project().equals(J.str(pl, "project")) && s.slot().equals(J.str(pl, "slot")));
		if (!scoped) {
			return false;
		}
		if (q.kinds() != null && !q.kinds().isEmpty() && !q.kinds().contains(J.str(pl, "kind"))) {
			return false;
		}
		return q.component() == null || J.strings(pl, "components").contains(q.component());
	}

	static double cosine(float[] a, float[] b) {
		double dot = 0;
		double na = 0;
		double nb = 0;
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
	}

	private static double sparseScore(SparseVec q, SparseVec d, Map<Integer, Integer> df, int n) {
		Map<Integer, Float> dm = new HashMap<>();
		for (int i = 0; i < d.indices().length; i++) {
			dm.put(d.indices()[i], d.values()[i]);
		}
		double s = 0;
		for (int i = 0; i < q.indices().length; i++) {
			Float w = dm.get(q.indices()[i]);
			if (w != null) {
				int f = df.getOrDefault(q.indices()[i], 0);
				double idf = Math.log(1 + (n - f + 0.5) / (f + 0.5));
				s += q.values()[i] * w * idf;
			}
		}
		return s;
	}

	@Override
	public String describe() {
		return "memory (" + points.size() + " points)";
	}

}
