package io.github.shadowx4fox.archindex.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.Chunk;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * One document of the architecture, put back together from its chunks — the bundle carries chunks, not the files. A
 * chunk's text is the export's context line, a blank line and the unit's body; the body is what goes back, in line
 * order. Lines no chunk holds (a heading with no text under it) are reported as gaps, never invented. Chunks the export
 * made rather than cut — an integration row inside its section, the descriptor line of a component — are left out.
 */
public class DocumentService {

	static final String NOTE = "Rebuilt from the indexed chunks, in line order: HTML comments are dropped, a Mermaid diagram is named in its place, blank runs are collapsed, and lines in gaps (headings with no text under them) are not in the content. Cite the file and its lines.";

	private final Bundles bundles;

	private final int maxChars;

	public DocumentService(Bundles bundles, int maxChars) {
		this.bundles = bundles;
		this.maxChars = maxChars;
	}

	record Piece(Chunk chunk, int start, int end) {
	}

	public Map<String, Object> document(String project, String ref, String version, Integer offset) {
		Bundle b = bundles.bundle(project, version);
		String file = file(b, ref);
		List<Piece> all = new ArrayList<>();
		for (Chunk c : b.chunks()) {
			List<Object> l = c.lines();
			if (file.equals(c.file()) && l.size() == 2 && l.get(0) instanceof Number s && l.get(1) instanceof Number e
					&& s.intValue() >= 1 && e.intValue() >= s.intValue()) {
				all.add(new Piece(c, s.intValue(), e.intValue()));
			}
		}
		// the parts of one unit share its range and keep the bundle's order: the sort is stable
		all.sort(Comparator.comparingInt(Piece::start).thenComparing(Comparator.comparingInt(Piece::end).reversed()));
		List<Piece> kept = all.stream()
			.filter((p) -> all.stream()
				.noneMatch((o) -> o != p && o.start() <= p.start() && p.end() <= o.end()
						&& (o.start() != p.start() || o.end() != p.end())))
			.toList();
		if (kept.isEmpty()) {
			throw new ToolException("no chunk of " + file + " in " + b.projectKey() + " " + b.version() + " names its lines");
		}
		StringBuilder text = new StringBuilder();
		List<List<Integer>> covered = new ArrayList<>();
		for (Piece p : kept) {
			if (!text.isEmpty()) {
				text.append("\n\n");
			}
			text.append(body(p.chunk()));
			List<Integer> last = covered.isEmpty() ? null : covered.get(covered.size() - 1);
			if (last != null && p.start() <= last.get(1) + 1) {
				last.set(1, Math.max(last.get(1), p.end()));
			}
			else {
				covered.add(new ArrayList<>(List.of(p.start(), p.end())));
			}
		}
		List<List<Integer>> gaps = new ArrayList<>();
		int next = 1;
		for (List<Integer> r : covered) {
			if (r.get(0) > next) {
				gaps.add(List.of(next, r.get(0) - 1));
			}
			next = r.get(1) + 1;
		}
		String content = text.toString();
		int from = Math.max(0, offset == null ? 0 : offset);
		if (from > content.length()) {
			throw new ToolException("offset " + from + " is past the end (" + content.length() + " characters)");
		}
		int to = maxChars > 0 ? Math.min(content.length(), from + maxChars) : content.length();
		Chunk first = kept.get(0).chunk();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("architecture_version", b.version());
		m.put("channel", b.channel());
		m.put("file", file);
		m.put("kind", first.kind());
		m.put("title", first.title());
		m.put("chunks", kept.size());
		m.put("lines", covered);
		m.put("gaps", gaps);
		m.put("note", NOTE);
		m.put("content", content.substring(from, to));
		if (from > 0 || to < content.length()) {
			m.put("range", Map.of("from", from, "to", to, "of", content.length()));
			if (to < content.length()) {
				m.put("next_offset", to);
			}
		}
		return m;
	}

	/** A file path as a hit names it, an ADR or TDR id, or a component — each exact, ignoring case. */
	String file(Bundle b, String ref) {
		if (ref == null || ref.isBlank()) {
			throw new ToolException("name a document: a file from a search hit, an ADR or TDR id, or a component");
		}
		String r = ref.trim().replaceFirst("^\\./", "").toLowerCase(Locale.ROOT);
		LinkedHashSet<String> files = new LinkedHashSet<>();
		b.chunks().forEach((c) -> files.add(c.file()));
		for (String f : files) {
			if (f != null && f.toLowerCase(Locale.ROOT).equals(r)) {
				return f;
			}
		}
		for (Chunk c : b.chunks()) {
			for (String k : new String[] { J.str(c.meta(), "adr"), J.str(c.meta(), "tdr") }) {
				if (k != null && k.toLowerCase(Locale.ROOT).equals(r)) {
					return c.file();
				}
			}
		}
		for (Map<String, Object> c : J.maps(b.ledger(), "components")) {
			for (String k : new String[] { J.str(c, "slug"), J.str(c, "name"), J.str(c, "internal_name") }) {
				if (k != null && k.toLowerCase(Locale.ROOT).equals(r) && J.str(c, "file") != null
						&& files.contains(J.str(c, "file"))) {
					return J.str(c, "file");
				}
			}
		}
		throw new ToolException("no document '" + ref + "' in " + b.projectKey() + " " + b.version() + "; documents: "
				+ String.join(", ", files.stream().filter((f) -> f != null).sorted().toList()));
	}

	/** The chunk's body: its context line off, and the line the export put before a component's or an integration block's text. */
	static String body(Chunk c) {
		String t = c.text();
		int i = t.indexOf("\n\n");
		t = i < 0 ? "" : t.substring(i + 2);
		boolean made = "component".equals(c.kind()) || ("integration".equals(c.kind()) && J.str(c.meta(), "integration") != null);
		int j = t.indexOf("\n\n");
		return made && j >= 0 ? t.substring(j + 2) : t;
	}

}
