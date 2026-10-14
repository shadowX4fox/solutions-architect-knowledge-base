package io.github.shadowx4fox.archindex.bundle;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MAJOR.MINOR.PATCH ordering of architecture versions; a pre-release sorts before its release. */
public final class Semver {

	private static final Pattern P = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

	public static final Comparator<String> ORDER = Semver::compare;

	private Semver() {
	}

	public static boolean valid(String v) {
		return v != null && P.matcher(v).matches();
	}

	public static int compare(String a, String b) {
		Matcher x = P.matcher(a);
		Matcher y = P.matcher(b);
		if (!x.matches() || !y.matches()) {
			return a.compareTo(b);
		}
		for (int i = 1; i <= 3; i++) {
			int c = Long.compare(Long.parseLong(x.group(i)), Long.parseLong(y.group(i)));
			if (c != 0) {
				return c;
			}
		}
		String pa = x.group(4);
		String pb = y.group(4);
		if (pa == null || pb == null) {
			return pa == null ? (pb == null ? 0 : 1) : -1;
		}
		return pa.compareTo(pb);
	}

}
