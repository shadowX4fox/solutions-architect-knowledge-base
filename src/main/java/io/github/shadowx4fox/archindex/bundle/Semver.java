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
			int c = numeric(x.group(i), y.group(i));
			if (c != 0) {
				return c;
			}
		}
		String pa = x.group(4);
		String pb = y.group(4);
		if (pa == null || pb == null) {
			return pa == null ? (pb == null ? 0 : 1) : -1;
		}
		// identifier by identifier: numeric ones by value and before alphanumeric ones, so rc.9 sorts before rc.10
		String[] ia = pa.split("\\.", -1);
		String[] ib = pb.split("\\.", -1);
		for (int i = 0; i < Math.min(ia.length, ib.length); i++) {
			boolean na = ia[i].matches("\\d+");
			boolean nb = ib[i].matches("\\d+");
			int c = na && nb ? numeric(ia[i], ib[i]) : na ? -1 : nb ? 1 : ia[i].compareTo(ib[i]);
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(ia.length, ib.length);
	}

	/** Two digit strings by value, of any length. */
	private static int numeric(String a, String b) {
		String x = a.replaceFirst("^0+(?=.)", "");
		String y = b.replaceFirst("^0+(?=.)", "");
		return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
	}

}
