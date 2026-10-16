package io.github.shadowx4fox.archindex.build;

/**
 * Where a page of text ends: at most {@code max} characters past {@code from}, never between the two halves of a
 * surrogate pair — a page that would hold only the first half takes both, so every page is valid UTF-8 on its own.
 */
public final class Paging {

	private Paging() {
	}

	public static int end(String text, int from, int max) {
		int to = max > 0 ? Math.min(text.length(), from + max) : text.length();
		if (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1)) && Character.isLowSurrogate(text.charAt(to))) {
			to = to - 1 > from ? to - 1 : to + 1;
		}
		return to;
	}

}
