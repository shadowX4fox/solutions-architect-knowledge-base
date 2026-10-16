package io.github.shadowx4fox.archindex.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.index.SparseEncoder.SparseVec;

/**
 * The in-process index kept in one file — a single machine with no Qdrant. Search is the memory index's; the points
 * are read at start and written whole, through a temporary file and an atomic move, each time the indexer finishes a
 * project. A file written for another model's dimensions is refused, as Qdrant refuses its collection.
 */
public class FileVectorIndex extends MemoryVectorIndex {

	private static final int MAGIC = 0x53414958; // SAIX

	private static final int FORMAT = 1;

	private final Path file;

	private final int dimensions;

	private volatile boolean dirty;

	public FileVectorIndex(Path file, int dimensions) {
		this.file = file.toAbsolutePath().normalize();
		this.dimensions = dimensions;
	}

	@Override
	public void ensure() {
		if (!Files.exists(file)) {
			return;
		}
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
				throw new IllegalStateException(file + " is not a vector index file of format " + FORMAT);
			}
			int size = in.readInt();
			if (size != dimensions) {
				throw new IllegalStateException(file + " holds " + size + "-dimension vectors; the embedding model gives "
						+ dimensions + ". A model switch needs a new file: set ARCHINDEX_FILE_PATH");
			}
			int n = in.readInt();
			points.clear();
			for (int i = 0; i < n; i++) {
				String id = in.readUTF();
				float[] dense = new float[size];
				for (int d = 0; d < size; d++) {
					dense[d] = in.readFloat();
				}
				int[] indices = new int[in.readInt()];
				float[] values = new float[indices.length];
				for (int s = 0; s < indices.length; s++) {
					indices[s] = in.readInt();
					values[s] = in.readFloat();
				}
				byte[] payload = new byte[in.readInt()];
				in.readFully(payload);
				points.put(id, new Point(id, dense, new SparseVec(indices, values), J.obj(payload)));
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("reading " + file + ": " + e.getMessage(), e);
		}
	}

	@Override
	public void upsert(List<Point> batch) {
		super.upsert(batch);
		dirty |= !batch.isEmpty();
	}

	@Override
	public void deleteSlot(String project, String slot) {
		long before = points.size();
		super.deleteSlot(project, slot);
		dirty |= points.size() != before;
	}

	@Override
	public void deleteSlotExcept(String project, String slot, java.util.Collection<String> keep) {
		long before = points.size();
		super.deleteSlotExcept(project, slot, keep);
		dirty |= points.size() != before;
	}

	@Override
	public boolean persistent() {
		return true;
	}

	@Override
	public synchronized void flush() {
		if (!dirty) {
			return;
		}
		dirty = false;
		try {
			Files.createDirectories(file.getParent());
			Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
			try {
				List<Point> all = List.copyOf(points.values());
				try (DataOutputStream out = new DataOutputStream(
						new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
					out.writeInt(MAGIC);
					out.writeInt(FORMAT);
					out.writeInt(dimensions);
					out.writeInt(all.size());
					for (Point p : all) {
						out.writeUTF(p.id());
						for (float f : p.dense()) {
							out.writeFloat(f);
						}
						out.writeInt(p.sparse().indices().length);
						for (int s = 0; s < p.sparse().indices().length; s++) {
							out.writeInt(p.sparse().indices()[s]);
							out.writeFloat(p.sparse().values()[s]);
						}
						byte[] payload = J.write(p.payload()).getBytes(StandardCharsets.UTF_8);
						out.writeInt(payload.length);
						out.write(payload);
					}
				}
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(tmp);
			}
		}
		catch (IOException e) {
			dirty = true;
			throw new UncheckedIOException("writing " + file + ": " + e.getMessage(), e);
		}
	}

	@Override
	public String describe() {
		return "file " + file + " (" + points.size() + " points)";
	}

}
