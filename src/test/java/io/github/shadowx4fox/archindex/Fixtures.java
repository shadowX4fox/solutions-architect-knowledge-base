package io.github.shadowx4fox.archindex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.bundle.Tgz;
import io.github.shadowx4fox.archindex.index.SparseEncoder;

/**
 * The synthetic bundles under {@code src/test/resources/bundles/order-desk/} — exported by the plugin from its own test
 * project (scripts/make-fixtures.ts): {@code 1.3.0-search} (the release tag, no kit yet), {@code 1.3.0} (with the
 * Hub API build pack), {@code 1.4.0} (a release with contract, budget, technology and integration changes, shipped
 * with changes.json), {@code 1.4.0-revised} (1.4.0 again, the kit regenerated: Hub API's security obligations moved, its
 * changes.json the same-version diff) and {@code draft}.
 */
public final class Fixtures {

	private Fixtures() {
	}

	public static Path bundle(String name) {
		try {
			return Path.of(Fixtures.class.getResource("/bundles/order-desk/" + name + "/manifest.json").toURI()).getParent();
		}
		catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	public static byte[] tgz(Path dir) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Tgz.pack(dir, out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static Path copy(Path dir, Path to) {
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : s.toList()) {
				Path t = to.resolve(dir.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(t);
				}
				else {
					Files.copy(p, t);
				}
			}
			return to;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Writes a file of a bundle and the sha256 and size the manifest lists for it, so the bundle still passes its check. */
	public static void rewrite(Path dir, String path, String content) {
		try {
			byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			Files.write(dir.resolve(path), bytes);
			java.util.Map<String, Object> m = J.obj(Files.readAllBytes(dir.resolve("manifest.json")));
			m.put("files", J.maps(m, "files").stream().map((f) -> {
				if (!path.equals(J.str(f, "path"))) {
					return f;
				}
				java.util.Map<String, Object> g = new java.util.LinkedHashMap<>(f);
				g.put("sha256", J.sha256(bytes));
				g.put("bytes", bytes.length);
				return g;
			}).toList());
			Files.writeString(dir.resolve("manifest.json"), J.write(m));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * A deterministic stand-in for the embedding model: the sparse encoder's terms hashed into 1,024 dimensions. Shared words
	 * make vectors close, which is enough to test the pipeline without a model.
	 */
	public static class HashingEmbeddingModel implements EmbeddingModel {

		public final List<String> seen = new ArrayList<>();

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<Embedding> out = new ArrayList<>();
			int i = 0;
			for (String t : request.getInstructions()) {
				seen.add(t);
				out.add(new Embedding(vector(t), i++));
			}
			return new EmbeddingResponse(out);
		}

		@Override
		public float[] embed(Document document) {
			return vector(document.getText());
		}

		static float[] vector(String text) {
			float[] v = new float[1024];
			SparseEncoder.SparseVec s = new SparseEncoder().document(text);
			for (int i = 0; i < s.indices().length; i++) {
				v[s.indices()[i] % 1024] += s.values()[i];
			}
			v[1023] += 0.01f;
			return v;
		}

	}

}
