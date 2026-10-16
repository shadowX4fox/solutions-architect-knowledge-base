package io.github.shadowx4fox.archindex.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * The bundle on the wire: a gzipped tar of the bundle directory, as {@code ci/publish-index.sh} sends it. Extraction
 * refuses links, absolute paths and any path that leaves the target, and stops at a size limit.
 */
public final class Tgz {

	public static class TooLarge extends IOException {

		@Serial
		private static final long serialVersionUID = 1L;

		TooLarge(long limit) {
			super("bundle larger than " + limit + " bytes");
		}

	}

	private Tgz() {
	}

	public static void extract(InputStream gz, Path target, long maxBytes) throws IOException {
		Files.createDirectories(target);
		Path root = target.toAbsolutePath().normalize();
		long total = 0;
		try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(gz), "UTF-8")) {
			TarArchiveEntry e;
			while ((e = tar.getNextEntry()) != null) {
				String name = e.getName();
				if (e.isSymbolicLink() || e.isLink()) {
					throw new IOException("link in bundle: " + name);
				}
				if (name.startsWith("/") || name.contains("\\")) {
					throw new IOException("unsafe path in bundle: " + name);
				}
				Path out = root.resolve(name).normalize();
				if (!out.startsWith(root)) {
					throw new IOException("path leaves the bundle: " + name);
				}
				if (e.isDirectory()) {
					Files.createDirectories(out);
					continue;
				}
				if (!e.isFile()) {
					continue;
				}
				Files.createDirectories(out.getParent());
				try (OutputStream os = Files.newOutputStream(out)) {
					byte[] buf = new byte[64 * 1024];
					int r;
					while ((r = tar.read(buf)) > 0) {
						total += r;
						if (total > maxBytes) {
							throw new TooLarge(maxBytes);
						}
						os.write(buf, 0, r);
					}
				}
			}
		}
	}

	public static void pack(Path dir, OutputStream out) throws IOException {
		GZIPOutputStream gz = new GZIPOutputStream(out);
		TarArchiveOutputStream tar = new TarArchiveOutputStream(gz, "UTF-8");
		tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
		tar.setAddPaxHeadersForNonAsciiNames(true);
		for (String rel : BundleChecker.listFiles(dir)) {
			Path f = dir.resolve(rel);
			TarArchiveEntry e = new TarArchiveEntry(f.toFile(), "./" + rel);
			tar.putArchiveEntry(e);
			Files.copy(f, tar);
			tar.closeArchiveEntry();
		}
		tar.finish();
		gz.finish();
		out.flush();
	}

}
