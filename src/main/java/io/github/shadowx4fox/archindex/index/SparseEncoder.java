package io.github.shadowx4fox.archindex.index;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sparse side of hybrid search: exact identifiers ({@code ADR-112}, {@code I-07}, {@code hub-api}, {@code UC-02})
 * that a dense embedding blurs. A token is a run of letters and digits joined by {@code - _ . /}; the whole compound
 * and each of its parts are terms, so {@code ADR-112} matches both {@code ADR-112} and {@code 112}. Accents fold, so
 * a Spanish architecture and an unaccented query meet. Terms hash to indices (FNV-1a); weights are {@code 1 + ln tf}
 * for a document and 1 for a query — the inverse document frequency is the store's (Qdrant's {@code idf} modifier).
 */
public final class SparseEncoder {

	public record SparseVec(int[] indices, float[] values) {

		public boolean isEmpty() {
			return indices.length == 0;
		}

	}

	private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[-_./][\\p{L}\\p{N}]+)*");

	private static final Pattern PART = Pattern.compile("[\\p{L}\\p{N}]+");

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");

	private static final Set<String> STOP = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how",
			"in", "is", "it", "of", "on", "or", "the", "to", "what", "when", "where", "which", "who", "why", "with", "does",
			"do", "el", "la", "los", "las", "un", "una", "y", "o", "de", "del", "en", "por", "para", "con", "que", "se",
			"es", "su", "al", "como", "cual", "cuando", "donde");

	public SparseVec document(String text) {
		Map<Integer, Integer> tf = counts(text);
		return toVec(tf, true);
	}

	public SparseVec query(String text) {
		return toVec(counts(text), false);
	}

	Map<Integer, Integer> counts(String text) {
		Map<Integer, Integer> tf = new LinkedHashMap<>();
		for (String t : terms(text)) {
			tf.merge(index(t), 1, Integer::sum);
		}
		return tf;
	}

	static Iterable<String> terms(String text) {
		String folded = MARKS.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)).replaceAll("")
			.toLowerCase();
		java.util.List<String> out = new java.util.ArrayList<>();
		Matcher m = TOKEN.matcher(folded);
		while (m.find()) {
			String tok = m.group();
			boolean compound = !PART.matcher(tok).matches();
			if (compound) {
				out.add(tok);
			}
			Matcher p = PART.matcher(tok);
			while (p.find()) {
				String part = p.group();
				if (!STOP.contains(part) && (part.length() > 1 || Character.isDigit(part.charAt(0)))) {
					out.add(part);
				}
			}
		}
		return out;
	}

	static int index(String term) {
		int h = 0x811c9dc5;
		for (byte b : term.getBytes(StandardCharsets.UTF_8)) {
			h ^= (b & 0xff);
			h *= 0x01000193;
		}
		return h & 0x7fffffff;
	}

	private static SparseVec toVec(Map<Integer, Integer> tf, boolean weighted) {
		TreeMap<Integer, Integer> sorted = new TreeMap<>(tf);
		int[] idx = new int[sorted.size()];
		float[] val = new float[sorted.size()];
		int i = 0;
		for (Map.Entry<Integer, Integer> e : sorted.entrySet()) {
			idx[i] = e.getKey();
			val[i] = weighted ? (float) (1 + Math.log(e.getValue())) : 1f;
			i++;
		}
		return new SparseVec(idx, val);
	}

}
