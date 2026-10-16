package io.github.shadowx4fox.archindex.build;

/** Where a page of text ends: at most {@code max} characters past {@code from}, never between the two halves of a surrogate pair. */
public final class Paging {

	private Paging() {
	}

	public static int end(String text, int from, int max) {
		int to = max > 0 ? Math.min(text.length(), from + max) : text.length();
		if (to < text.length() && to > from + 1 && Character.isHighSurrogate(text.charAt(to - 1))) {
			to--;
		}
		return to;
	}

}
