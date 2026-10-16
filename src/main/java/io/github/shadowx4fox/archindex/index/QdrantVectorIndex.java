package io.github.shadowx4fox.archindex.index;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ValueFactory.value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.github.shadowx4fox.archindex.ArchIndexProperties;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.Modifier;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.VectorOutput;

/**
 * Qdrant over gRPC. One collection for every project: a named dense vector ({@code dense}, cosine) and a named sparse
 * vector ({@code sparse}, Qdrant's {@code idf} modifier); keyword payload indexes on the fields every query filters
 * by. A search is two prefetches under the same filter, fused by reciprocal rank — Spring AI's QdrantVectorStore is
 * dense only, so this class speaks to the client directly.
 */
public class QdrantVectorIndex implements VectorIndex, AutoCloseable {

	static final String DENSE = "dense";

	static final String SPARSE = "sparse";

	private static final List<String> KEYWORD_FIELDS = List.of("project", "slot", "kind", "components", "adrs",
			"content_hash", "doc_language", "chunk_id");

	private final QdrantClient client;

	private final String collection;

	private final int dimensions;

	public QdrantVectorIndex(ArchIndexProperties.Qdrant q, int dimensions) {
		QdrantGrpcClient.Builder b = QdrantGrpcClient.newBuilder(q.host(), q.port(), q.tls());
		if (q.apiKey() != null && !q.apiKey().isBlank()) {
			b.withApiKey(q.apiKey());
		}
		this.client = new QdrantClient(b.build());
		this.collection = q.collection();
		this.dimensions = dimensions;
	}

	@Override
	public void ensure() {
		if (get(client.collectionExistsAsync(collection))) {
			// a collection made for another model holds vectors of another size: refuse rather than mix them
			var held = get(client.getCollectionInfoAsync(collection)).getConfig().getParams().getVectorsConfig();
			long size = held.hasParamsMap() && held.getParamsMap().getMapMap().containsKey(DENSE)
					? held.getParamsMap().getMapMap().get(DENSE).getSize() : -1;
			if (size != dimensions) {
				throw new IllegalStateException("qdrant collection " + collection + " holds " + size
						+ "-dimension vectors; the embedding model gives " + dimensions
						+ ". A model switch needs a new collection: set QDRANT_COLLECTION");
			}
			return;
		}
		get(client.createCollectionAsync(CreateCollection.newBuilder()
			.setCollectionName(collection)
			.setVectorsConfig(VectorsConfig.newBuilder()
				.setParamsMap(VectorParamsMap.newBuilder()
					.putMap(DENSE, VectorParams.newBuilder().setSize(dimensions).setDistance(Distance.Cosine).build())))
			.setSparseVectorsConfig(SparseVectorConfig.newBuilder()
				.putMap(SPARSE, SparseVectorParams.newBuilder().setModifier(Modifier.Idf).build()))
			.build()));
		for (String f : KEYWORD_FIELDS) {
			get(client.createPayloadIndexAsync(collection, f, PayloadSchemaType.Keyword, null, true, null, null));
		}
	}

	@Override
	public Map<String, float[]> densesByHash(String project, Collection<String> contentHashes) {
		Map<String, float[]> out = new HashMap<>();
		List<String> all = new ArrayList<>(contentHashes);
		for (int i = 0; i < all.size(); i += 256) {
			Filter f = Filter.newBuilder()
				.addMust(matchKeyword("project", project))
				.addMust(matchKeywords("content_hash", all.subList(i, Math.min(all.size(), i + 256))))
				.build();
			ScrollPoints.Builder req = ScrollPoints.newBuilder()
				.setCollectionName(collection)
				.setFilter(f)
				.setLimit(256)
				.setWithPayload(WithPayloadSelectorFactory.include(List.of("content_hash")))
				.setWithVectors(WithVectorsSelectorFactory.include(List.of(DENSE)));
			while (true) {
				ScrollResponse r = get(client.scrollAsync(req.build()));
				for (RetrievedPoint p : r.getResultList()) {
					String h = p.getPayloadMap().get("content_hash").getStringValue();
					VectorOutput v = p.getVectors().getVectors().getVectorsMap().get(DENSE);
					if (v != null) {
						out.putIfAbsent(h, dense(v));
					}
				}
				if (!r.hasNextPageOffset()) {
					break;
				}
				req.setOffset(r.getNextPageOffset());
			}
		}
		return out;
	}

	@Override
	public void upsert(List<Point> points) {
		List<PointStruct> batch = new ArrayList<>();
		for (Point p : points) {
			batch.add(PointStruct.newBuilder()
				.setId(PointIdFactory.id(UUID.fromString(p.id())))
				.setVectors(VectorsFactory.namedVectors(Map.of(DENSE, VectorFactory.vector(p.dense()), SPARSE,
						VectorFactory.vector(boxed(p.sparse().values()), boxed(p.sparse().indices())))))
				.putAllPayload(payload(p.payload()))
				.build());
		}
		if (!batch.isEmpty()) {
			get(client.upsertAsync(collection, batch));
		}
	}

	@Override
	public void deleteSlot(String project, String slot) {
		get(client.deleteAsync(collection, slotFilter(project, slot)));
	}

	@Override
	public long count(String project, String slot) {
		return get(client.countAsync(collection, slotFilter(project, slot), true));
	}

	private static Filter slotFilter(String project, String slot) {
		return Filter.newBuilder().addMust(matchKeyword("project", project)).addMust(matchKeyword("slot", slot)).build();
	}

	@Override
	public List<Hit> search(Query q) {
		Filter.Builder scopes = Filter.newBuilder();
		for (Scope s : q.scopes()) {
			scopes.addShould(io.qdrant.client.ConditionFactory.filter(slotFilter(s.project(), s.slot())));
		}
		Filter.Builder f = Filter.newBuilder().addMust(io.qdrant.client.ConditionFactory.filter(scopes.build()));
		if (q.kinds() != null && !q.kinds().isEmpty()) {
			f.addMust(matchKeywords("kind", q.kinds()));
		}
		if (q.component() != null) {
			f.addMust(matchKeyword("components", q.component()));
		}
		Filter filter = f.build();
		QueryPoints.Builder req = QueryPoints.newBuilder()
			.setCollectionName(collection)
			.addPrefetch(PrefetchQuery.newBuilder()
				.setQuery(QueryFactory.nearest(q.dense()))
				.setUsing(DENSE)
				.setFilter(filter)
				.setLimit(q.candidates()));
		if (!q.sparse().isEmpty()) {
			req.addPrefetch(PrefetchQuery.newBuilder()
				.setQuery(QueryFactory.nearest(boxed(q.sparse().values()), boxed(q.sparse().indices())))
				.setUsing(SPARSE)
				.setFilter(filter)
				.setLimit(q.candidates()));
		}
		Rrf.Builder rrf = Rrf.newBuilder().setK(60).addWeights(1f);
		if (!q.sparse().isEmpty()) {
			rrf.addWeights((float) q.sparseWeight());
		}
		req.setQuery(QueryFactory.rrf(rrf.build()))
			.setLimit(q.limit())
			.setWithPayload(WithPayloadSelectorFactory.include(List.of("chunk_id", "project", "slot")));
		List<Hit> out = new ArrayList<>();
		for (ScoredPoint p : get(client.queryAsync(req.build()))) {
			Map<String, Value> pl = p.getPayloadMap();
			out.add(new Hit(pl.get("chunk_id").getStringValue(), pl.get("project").getStringValue(),
					pl.get("slot").getStringValue(), p.getScore()));
		}
		return out;
	}

	@Override
	public boolean persistent() {
		return true;
	}

	@Override
	public String describe() {
		return "qdrant collection " + collection;
	}

	@Override
	public void close() {
		client.close();
	}

	private static Map<String, Value> payload(Map<String, Object> m) {
		Map<String, Value> out = new HashMap<>();
		m.forEach((k, v) -> out.put(k, toValue(v)));
		return out;
	}

	private static Value toValue(Object v) {
		if (v == null) {
			return io.qdrant.client.ValueFactory.nullValue();
		}
		if (v instanceof Collection<?> c) {
			return io.qdrant.client.ValueFactory.list(c.stream().map(QdrantVectorIndex::toValue).toList());
		}
		if (v instanceof Number n) {
			return (n instanceof Double || n instanceof Float) ? value(n.doubleValue()) : value(n.longValue());
		}
		if (v instanceof Boolean b) {
			return value(b);
		}
		return value(String.valueOf(v));
	}

	/** A stored dense vector: in {@code dense} since the vector output was split by kind, in {@code data} from older servers. */
	@SuppressWarnings("deprecation")
	private static float[] dense(VectorOutput v) {
		return floats(v.getDense().getDataCount() > 0 ? v.getDense().getDataList() : v.getDataList());
	}

	private static float[] floats(List<Float> l) {
		float[] a = new float[l.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = l.get(i);
		}
		return a;
	}

	private static List<Float> boxed(float[] a) {
		List<Float> l = new ArrayList<>(a.length);
		for (float x : a) {
			l.add(x);
		}
		return l;
	}

	private static List<Integer> boxed(int[] a) {
		List<Integer> l = new ArrayList<>(a.length);
		for (int x : a) {
			l.add(x);
		}
		return l;
	}

	private static <T> T get(Future<T> f) {
		try {
			return f.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		catch (ExecutionException e) {
			throw new IllegalStateException("qdrant: " + e.getCause().getMessage(), e.getCause());
		}
	}

}
