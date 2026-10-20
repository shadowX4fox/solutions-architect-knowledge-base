package io.github.shadowx4fox.archindex.escalation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.shadowx4fox.archindex.bundle.J;

/**
 * The plugin's Tier 0 request issue ({@code scripts/shared/tier0.js}): {@code validate}, {@code issueTitle},
 * {@code labelsOf} and {@code issueBody}, field for field, so an issue the server previews is the one
 * {@code tier0.js render} writes and {@code architecture-tier0.yml} reads back ({@code requestOf}). {@code Tier0Test}
 * holds it to the plugin's own output. The kinds, their labels, their required fields and the status moves are the
 * bundle's ({@code manifest.support}); the TDR statuses and drivers are {@code tdr-check.js}'s, which the bundle does
 * not carry.
 * <p>
 * The request is taken as the agent sent it — nothing is trimmed that {@code tier0.js} does not trim — and the fields
 * are JavaScript values: {@code filled}, {@code ===}, {@code Number()} and {@code JSON.stringify(fields, null, 2)}
 * behave here as they do there.
 */
public final class Tier0 {

	public static final String LABEL = "architecture-tier0";

	/** The id a request carries until the architecture repository writes its record. */
	public static final String UNNUMBERED = "T0-?";

	public static final String WONT_FIX = "Won't Fix";

	/** {@code supervision}: a build no person attends files without the key; a person applies it. */
	public static final String UNATTENDED = "unattended";

	/**
	 * {@code tdr-check.js} STATUSES and DRIVERS as of the plugin's v10.7.0, for a release exported before v10.7.1, which
	 * lists neither; a newer release names its own ({@code support.tdr_statuses}, {@code support.tdr_drivers}).
	 */
	public static final List<String> STATUSES = List.of("Open", "Planned", "In Progress", "Resolved", WONT_FIX);

	public static final List<String> DRIVERS = List.of("Strategy", "Architecture", "Talent", "Process");

	private static final Pattern TDR_ID = Pattern.compile("TDR-\\d{3,}");

	private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

	private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)");

	private static final Pattern INDEX = Pattern.compile("0|[1-9]\\d{0,9}");

	private Tier0() {
	}

	public record Evidence(String where, String note) {
	}

	/**
	 * What {@code tier0.js render} takes ({@code request.json}), as the agent sent it; {@code supervision} is
	 * {@code "unattended"} for a build no person attends, otherwise attended.
	 */
	public record Request(String kind, String title, String request, List<String> components, String requestedBy,
			List<Evidence> evidence, Map<String, Object> fields, String supervision) {

		public Request {
			components = components == null ? List.of() : components;
			evidence = evidence == null ? List.of() : evidence;
			fields = fields == null ? Map.of() : fields;
		}

		public Request(String kind, String title, String request, List<String> components, String requestedBy,
				List<Evidence> evidence, Map<String, Object> fields) {
			this(kind, title, request, components, requestedBy, evidence, fields, null);
		}

		public Request withComponents(List<String> c) {
			return new Request(kind, title, request, c, requestedBy, evidence, fields, supervision);
		}

		/** {@code unattended}: {@code req.supervision === 'unattended'}. */
		public boolean unattended() {
			return UNATTENDED.equals(supervision);
		}

	}

	/** One of {@code support.tier0_kinds}. */
	public record Kind(String label, List<String> required, boolean evidence) {
	}

	/**
	 * {@code support.tier0_kinds}, {@code support.tdr_moves}, {@code support.tdr_statuses} and
	 * {@code support.tdr_drivers}, as the bundle lists them.
	 */
	public record Policy(Map<String, Kind> kinds, Map<String, List<String>> moves, List<String> statuses, List<String> drivers) {

		public static Policy of(Map<String, Object> support) {
			Map<String, Kind> kinds = new LinkedHashMap<>();
			Map<String, Object> listed = J.map(support, "tier0_kinds");
			listed.keySet().forEach((k) -> {
				Map<String, Object> m = J.map(listed, k);
				kinds.put(k, new Kind(J.str(m, "label"), J.strings(m, "required"), Boolean.TRUE.equals(m.get("evidence"))));
			});
			Map<String, List<String>> moves = new LinkedHashMap<>();
			J.map(support, "tdr_moves").forEach((k, v) -> moves.put(k, v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of()));
			return new Policy(kinds, moves, listed(support, "tdr_statuses", STATUSES), listed(support, "tdr_drivers", DRIVERS));
		}

		private static List<String> listed(Map<String, Object> support, String key, List<String> before) {
			return support.get(key) instanceof List<?> l && !l.isEmpty() ? l.stream().map(String::valueOf).toList() : before;
		}

	}

	/** What the release's ledger holds of the records a request names: each TDR's status, and the ADR ids. */
	public record Ledger(Map<String, String> tdrs, Set<String> adrs, String version) {
	}

	/** {@code validate()}: an empty list when the request is a Tier 0 write as written. */
	public static List<String> validate(Request r, Policy p) {
		List<String> errors = new ArrayList<>();
		Kind k = r.kind() == null ? null : p.kinds().get(r.kind());
		if (k == null) {
			return List.of("kind \"" + (r.kind() == null ? "" : r.kind()) + "\" is not a Tier 0 write — one of "
					+ String.join(", ", p.kinds().keySet()));
		}
		if (!filled(r.title())) {
			errors.add("title is required");
		}
		if (!filled(r.request())) {
			errors.add("request (what the developer asked for, in their words) is required");
		}
		Map<String, Object> f = r.fields();
		k.required().stream().filter((x) -> !filled(f.get(x))).forEach((x) -> errors.add("fields." + x + " is required for " + r.kind()));
		if (k.evidence() && r.evidence().stream().noneMatch((e) -> e != null && filled(e.where()))) {
			errors.add(r.kind() + " needs evidence: where it shows in the service (repository, commit, file:line)");
		}
		if ("tdr-status".equals(r.kind())) {
			Object tdr = f.get("tdr");
			Object to = f.get("to");
			if (truthy(tdr) && !TDR_ID.matcher(string(tdr)).matches()) {
				errors.add("fields.tdr \"" + string(tdr) + "\" is not a TDR id");
			}
			if (truthy(to) && !(to instanceof String s && p.statuses().contains(s))) {
				errors.add("fields.to \"" + string(to) + "\" is not a status — one of " + String.join(" · ", p.statuses()));
			}
			if ("Planned".equals(to) && !DATE.matcher(string(truthy(f.get("target_date")) ? f.get("target_date") : "")).matches()) {
				errors.add("a move to Planned needs fields.target_date (YYYY-MM-DD)");
			}
			if (WONT_FIX.equals(to)) {
				errors.add("a move to Won't Fix is a decision, not a Tier 0 write — the architect makes it");
			}
		}
		Object driver = f.get("driver");
		if ("tdr-workaround".equals(r.kind()) && truthy(driver) && !(driver instanceof String s && p.drivers().contains(s))) {
			errors.add("fields.driver \"" + string(driver) + "\" is not one of " + String.join(" · ", p.drivers()));
		}
		if ("doc-fix".equals(r.kind())) {
			Object file = f.get("file");
			if (truthy(file) && (!string(file).startsWith("docs/") || List.of(string(file).split("/", -1)).contains(".."))) {
				errors.add("fields.file \"" + string(file) + "\" is not under docs/");
			}
			if (f.get("line") != null) {
				double line = number(f.get("line"));
				if (!(Double.isFinite(line) && line == Math.floor(line) && line >= 1)) {
					errors.add("fields.line is a line number");
				}
			}
			if (filled(f.get("before")) && strictlyEqual(f.get("before"), f.get("after"))) {
				errors.add("fields.before and fields.after are the same text");
			}
			String both = (truthy(f.get("before")) ? string(f.get("before")) : "") + (truthy(f.get("after")) ? string(f.get("after")) : "");
			if (both.contains("\n")) {
				errors.add("a doc-fix replaces text inside one line");
			}
		}
		return errors;
	}

	/**
	 * The server's own rules, on top of {@code validate()}: the title is the issue's title, and each piece of evidence is
	 * one list item that {@code requestOf} reads back — a line break in either would change what the issue says.
	 */
	public static List<String> oneLine(Request r) {
		List<String> errors = new ArrayList<>();
		if (r.title() != null && Escalate.trim(r.title()).matches("(?s).*[\\r\\n].*")) {
			errors.add("title must be one line");
		}
		if (r.evidence().stream().filter(Objects::nonNull).anyMatch((e) -> breaks(e.where()) || breaks(e.note()))) {
			errors.add("evidence: where and note are one line each");
		}
		return errors;
	}

	/**
	 * What {@code prepare} checks against the architecture once the request is valid, on the release's ledger: a
	 * workaround's ADRs exist; a status move's TDR exists and its status takes the arrow ({@code tdr-check.js move}).
	 */
	public static List<String> against(Request r, Policy p, Ledger l) {
		return against(r, p, l, new ArrayList<>());
	}

	/**
	 * The ledger checks, and in {@code assumed} what a move takes for granted. The release is behind main: Tier 0 writes merge
	 * between releases, and the architecture repository checks a move against main ({@code tier0.js prepare}). A move refused
	 * from the released status but valid from one a Tier 0 write may have set since — reachable along the moves, never through
	 * Won't Fix, which only the architect sets — is previewed, with what it assumes; a move to the released status itself
	 * stays refused.
	 */
	public static List<String> against(Request r, Policy p, Ledger l, List<String> assumed) {
		List<String> errors = new ArrayList<>();
		Map<String, Object> f = r.fields();
		if ("tdr-workaround".equals(r.kind())) {
			list(f.get("adrs")).stream().filter((a) -> !l.adrs().contains(a)).forEach((a) -> errors.add("fields.adrs: " + a + " does not exist"));
		}
		if ("tdr-status".equals(r.kind())) {
			String tdr = string(f.get("tdr"));
			String status = l.tdrs().get(tdr);
			if (status == null) {
				errors.add(tdr + " has no record under tdr/");
			}
			else {
				String to = string(f.get("to"));
				String reason = move(p, status, to);
				List<String> from = reason == null ? List.of() : since(p, status, to);
				if (!from.isEmpty()) {
					assumed.add(tdr + " is " + status + " in " + l.version() + "; this move assumes it is " + String.join(" or ", from)
							+ " in main — the architecture repository checks it");
				}
				else if (reason != null) {
					errors.add(tdr + ": " + status + " → " + to + " is refused — " + reason);
				}
			}
		}
		return errors;
	}

	/** The statuses a Tier 0 write may have moved a TDR to since the release, from which {@code to} is a move. */
	static List<String> since(Policy p, String released, String to) {
		String f = canon(p, released);
		String t = canon(p, to);
		if (f == null || t == null || f.equals(t)) {
			return List.of();
		}
		Set<String> seen = new LinkedHashSet<>(List.of(f));
		Deque<String> next = new ArrayDeque<>(List.of(f));
		List<String> out = new ArrayList<>();
		while (!next.isEmpty()) {
			for (String s : p.moves().getOrDefault(next.poll(), List.of())) {
				String c = canon(p, s);
				if (c == null || WONT_FIX.equals(c) || !seen.add(c)) {
					continue;
				}
				next.add(c);
				if (move(p, c, t) == null) {
					out.add(c);
				}
			}
		}
		return out;
	}

	/** {@code tdr-check.js move}: null when the arrow is allowed, otherwise why not. */
	static String move(Policy p, String from, String to) {
		String f = canon(p, from);
		String t = canon(p, to);
		if (f == null) {
			return "`" + from + "` is not a status";
		}
		if (t == null) {
			return "`" + to + "` is not a status — one of " + String.join(" · ", p.statuses());
		}
		if (f.equals(t)) {
			return "already `" + t + "`";
		}
		List<String> moves = p.moves().getOrDefault(f, List.of());
		if (moves.contains(t)) {
			return null;
		}
		String via = moves.stream().filter((x) -> p.moves().getOrDefault(x, List.of()).contains(t)).findFirst().orElse(null);
		if (via != null) {
			return "never straight to `" + t + "` — the arrow is " + f + " ──► " + via + " ──► " + t;
		}
		return "no arrow from `" + f + "` to `" + t + "` — from `" + f + "` the moves are "
				+ (moves.isEmpty() ? "none (terminal)" : String.join(", ", moves.stream().map((x) -> "`" + x + "`").toList()));
	}

	private static String canon(Policy p, String s) {
		return p.statuses().stream().filter((x) -> x.equalsIgnoreCase(s)).findFirst().orElse(null);
	}

	/** {@code issueTitle}, {@code labelsOf} and {@code issueBody}. */
	public static Escalate.Issue issue(Request r, Policy p, String architecture) {
		return new Escalate.Issue("[" + UNNUMBERED + "] " + Escalate.trim(string(r.title())),
				r.unattended() ? List.of("tier0:" + r.kind()) : List.of(LABEL, "tier0:" + r.kind()), body(r, p, architecture));
	}

	static String body(Request r, Policy p, String architecture) {
		String fields = stringify(r.fields());
		String rq = Escalate.trim((r.request() == null ? "" : r.request()).replaceAll("\r\n?", "\n"));
		Kind kind = p.kinds().get(r.kind());
		String label = kind == null || kind.label() == null ? "" : kind.label();
		Map<String, String> facts = new LinkedHashMap<>();
		facts.put("Architecture", or(architecture));
		facts.put("Kind", "`" + r.kind() + "` — " + label);
		facts.put("Requested by", or(r.requestedBy()));
		facts.put("Components", or(String.join(", ", list(r.components()))));
		List<String> w = new ArrayList<>();
		w.add("| | |");
		w.add("|---|---|");
		facts.forEach((k, v) -> w.add("| " + k + " | " + Escalate.cell(v) + " |"));
		w.addAll(List.of("", "## Request", "", fence(rq) + "text", rq, fence(rq), "", "## Evidence", ""));
		List<String> ev = r.evidence()
			.stream()
			.filter((e) -> e != null && filled(e.where()))
			.map((e) -> "- " + e.where() + (filled(e.note()) ? " — " + e.note() : ""))
			.toList();
		w.addAll(ev.isEmpty() ? List.of("- None.") : ev);
		w.addAll(List.of("", "## Fields", "", fence(fields) + "json", fields, fence(fields), ""));
		w.add(r.unattended()
				? "_Tier 0, filed by an unattended build agent: a person reviews it and applies the label `" + LABEL
						+ "` — that is the approval; the architecture repository then writes it (architecture-tier0.yml), and a "
						+ "request that is no Tier 0 write is refused there, with the reason._"
				: "_Tier 0: the architecture repository writes it once the issue carries the label `" + LABEL
						+ "` (architecture-tier0.yml); a request that is no Tier 0 write is refused there, with the reason._");
		w.add("<!-- sa-tier0 -->");
		w.add("");
		return String.join("\n", w);
	}

	// ---- JavaScript's values, as tier0.js sees the request

	/** {@code filled}: a list with an item that is not blank, or a value that is not blank. */
	static boolean filled(Object v) {
		if (v instanceof List<?> l) {
			return !list(l).isEmpty();
		}
		return v != null && !Escalate.trim(string(v)).isEmpty();
	}

	/** {@code list}: each item as text, trimmed, the empty ones dropped; a value that is no list is a list of one. */
	static List<String> list(Object v) {
		List<?> l = v instanceof List<?> x ? x : v == null || "".equals(v) ? List.of() : List.of(v);
		return l.stream().map((x) -> Escalate.trim(string(x))).filter((x) -> !x.isEmpty()).toList();
	}

	static boolean truthy(Object v) {
		if (v == null) {
			return false;
		}
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof Number n) {
			double d = n.doubleValue();
			return d != 0 && !Double.isNaN(d);
		}
		if (v instanceof String s) {
			return !s.isEmpty();
		}
		return true;
	}

	/** {@code String(v)}. */
	static String string(Object v) {
		if (v == null) {
			return "null";
		}
		if (v instanceof Number n) {
			return numeral(n.doubleValue());
		}
		if (v instanceof List<?> l) {
			return String.join(",", l.stream().map((x) -> x == null ? "" : string(x)).toList());
		}
		if (v instanceof Map<?, ?>) {
			return "[object Object]";
		}
		return String.valueOf(v);
	}

	/** {@code Number(v)}. */
	public static double number(Object v) {
		if (v == null) {
			return 0;
		}
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		if (v instanceof Boolean b) {
			return b ? 1 : 0;
		}
		String s = Escalate.trim(string(v));
		if (s.isEmpty()) {
			return 0;
		}
		if (DECIMAL.matcher(s).matches()) {
			return Double.parseDouble(s);
		}
		for (String[] radix : new String[][] { { "0x", "16" }, { "0o", "8" }, { "0b", "2" } }) {
			if (s.length() > 2 && s.substring(0, 2).equalsIgnoreCase(radix[0])) {
				try {
					return new BigInteger(s.substring(2), Integer.parseInt(radix[1])).doubleValue();
				}
				catch (NumberFormatException e) {
					return Double.NaN;
				}
			}
		}
		return Double.NaN;
	}

	/** {@code ===}: numbers by value, text by text; two lists or two objects are never the same. */
	static boolean strictlyEqual(Object a, Object b) {
		if (a instanceof Number x && b instanceof Number y) {
			return x.doubleValue() == y.doubleValue();
		}
		if (a == null || b == null) {
			return a == b;
		}
		if (a instanceof List<?> || a instanceof Map<?, ?>) {
			return a == b;
		}
		return a.getClass() == b.getClass() && a.equals(b);
	}

	/** {@code Number.prototype.toString()}: the shortest digits that read back as the same double. */
	static String numeral(double d) {
		if (Double.isNaN(d)) {
			return "NaN";
		}
		if (Double.isInfinite(d)) {
			return d > 0 ? "Infinity" : "-Infinity";
		}
		if (d == 0) {
			return "0";
		}
		BigDecimal exact = new BigDecimal(Math.abs(d));
		BigDecimal bd = exact;
		for (int p = 1; p <= 17; p++) {
			bd = exact.round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN));
			if (bd.doubleValue() == Math.abs(d)) {
				break;
			}
		}
		bd = bd.stripTrailingZeros();
		String digits = bd.unscaledValue().toString();
		int k = digits.length();
		int n = k - bd.scale();
		String s;
		if (k <= n && n <= 21) {
			s = digits + "0".repeat(n - k);
		}
		else if (0 < n && n <= 21) {
			s = digits.substring(0, n) + "." + digits.substring(n);
		}
		else if (-6 < n && n <= 0) {
			s = "0." + "0".repeat(-n) + digits;
		}
		else {
			int e = n - 1;
			s = digits.charAt(0) + (k == 1 ? "" : "." + digits.substring(1)) + "e" + (e < 0 ? "-" : "+") + Math.abs(e);
		}
		return d < 0 ? "-" + s : s;
	}

	/** {@code JSON.stringify(v, null, 2)}: two-space indentation, an object's keys in JavaScript's order. */
	public static String stringify(Object v) {
		return stringify(v, "");
	}

	private static String stringify(Object v, String indent) {
		String in = indent + "  ";
		if (v == null) {
			return "null";
		}
		if (v instanceof Boolean b) {
			return b.toString();
		}
		if (v instanceof Number n) {
			double d = n.doubleValue();
			return Double.isFinite(d) ? numeral(d) : "null";
		}
		if (v instanceof Map<?, ?> m) {
			if (m.isEmpty()) {
				return "{}";
			}
			List<String> rows = new ArrayList<>();
			keys(m).forEach((key) -> rows.add(in + quote(key) + ": " + stringify(m.get(key), in)));
			return "{\n" + String.join(",\n", rows) + "\n" + indent + "}";
		}
		if (v instanceof List<?> l) {
			if (l.isEmpty()) {
				return "[]";
			}
			return "[\n" + String.join(",\n", l.stream().map((x) -> in + stringify(x, in)).toList()) + "\n" + indent + "]";
		}
		return quote(String.valueOf(v));
	}

	/** An object's own keys in JavaScript's order: the array indexes ascending, then the others as they were added. */
	private static List<Object> keys(Map<?, ?> m) {
		List<Object> indexes = new ArrayList<>();
		List<Object> others = new ArrayList<>();
		for (Object k : m.keySet()) {
			String s = String.valueOf(k);
			(INDEX.matcher(s).matches() && Long.parseLong(s) < 4294967295L ? indexes : others).add(k);
		}
		indexes.sort((a, b) -> Long.compare(Long.parseLong(String.valueOf(a)), Long.parseLong(String.valueOf(b))));
		indexes.addAll(others);
		return indexes;
	}

	/** {@code JSON.stringify} of a string: a lone surrogate and a control character escaped, lower-case hex. */
	static String quote(Object key) {
		String s = String.valueOf(key);
		StringBuilder b = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\b' -> b.append("\\b");
				case '\f' -> b.append("\\f");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
						b.append(c).append(s.charAt(++i));
					}
					else if (c < 0x20 || Character.isSurrogate(c)) {
						b.append(String.format("\\u%04x", (int) c));
					}
					else {
						b.append(c);
					}
				}
			}
		}
		return b.append('"').toString();
	}

	/** {@code fenceFor}: longer than any run of backticks in the text, and at least three. */
	private static String fence(String text) {
		int longest = 2;
		java.util.regex.Matcher m = Pattern.compile("`+").matcher(text);
		while (m.find()) {
			longest = Math.max(longest, m.group().length());
		}
		return "`".repeat(longest + 1);
	}

	private static boolean breaks(String s) {
		return s != null && (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0);
	}

	/** {@code x || '—'}. */
	private static String or(String s) {
		return s == null || s.isEmpty() ? "—" : s;
	}

}
