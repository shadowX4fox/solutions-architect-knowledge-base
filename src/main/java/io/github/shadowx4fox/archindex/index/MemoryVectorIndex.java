package io.github.shadowx4fox.archindex.index;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.index.SparseEncoder.SparseVec;

/**
 * Brute force in process — development and tests. The same candidate lists as Qdrant: cosine on the dense vector, a
 * sparse dot product weighted by inverse document frequency over the points in scope ({@code ln(1 + (n − df + 0.5) /
 * (df + 0.5))}, Qdrant's {@code idf} modifier).
 */
public class MemoryVectorIndex implements VectorIndex {

	protected final Map<String, Point> points = new ConcurrentHashMap<>();

	private final Map<String, String> marks = new ConcurrentHashMap<>();

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
	public void deleteSlotExcept(String project, String slot, Collection<String> keep) {
		Set<String> k = new HashSet<>(keep);
		points.values().removeIf((p) -> inSlot(p, project, slot) && !k.contains(J.str(p.payload(), "chunk_id")));
	}

	@Override
	public void markSlot(String project, String slot, String signature, int chunks) {
		marks.put(project + "/" + slot, signature);
	}

	@Override
	public void unmarkSlot(String project, String slot) {
		marks.remove(project + "/" + slot);
	}

	@Override
	public Optional<String> slotSignature(String project, String slot) {
		return Optional.ofNullable(marks.get(project + "/" + slot));
	}

	@Override
	public long count(String project, String slot) {
		return points.values().stream().filter((p) -> inSlot(p, project, slot)).count();
	}

	private static boolean inSlot(Point p, String project, String slot) {
		return project.equals(J.str(p.payload(), "project")) && slot.equals(J.str(p.payload(), "slot"));
	}

	@Override
	public Candidates candidates(Query q) {
		List<Point> pool = points.values().stream().filter((p) -> matches(p, q)).toList();
		Map<Integer, Integer> df = new HashMap<>();
		for (Point p : pool) {
			for (int i : p.sparse().indices()) {
				df.merge(i, 1, Integer::sum);
			}
		}
		int n = pool.size();
		// hits made from the pooled points: the indexer may remove one from the map while this search runs
		List<Hit> dense = pool.stream()
			.map((p) -> hit(p, cosine(q.dense(), p.dense())))
			.sorted(Comparator.comparingDouble((Hit h) -> -h.score()))
			.limit(q.candidates())
			.toList();
		List<Hit> sparse = pool.stream()
			.map((p) -> hit(p, sparseScore(q.sparse(), p.sparse(), df, n)))
			.filter((h) -> h.score() > 0)
			.sorted(Comparator.comparingDouble((Hit h) -> -h.score()))
			.limit(q.candidates())
			.toList();
		return new Candidates(dense, sparse);
	}

	private static Hit hit(Point p, double score) {
		return new Hit(J.str(p.payload(), "chunk_id"), J.str(p.payload(), "project"), J.str(p.payload(), "slot"), score);
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
