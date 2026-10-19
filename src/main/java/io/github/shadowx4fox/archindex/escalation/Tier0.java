package io.github.shadowx4fox.archindex.escalation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.shadowx4fox.archindex.bundle.J;

/**
 * The plugin's Tier 0 request issue ({@code scripts/shared/tier0.js}): {@code issueTitle}, {@code labelsOf} and
 * {@code issueBody}, so an issue the server previews reads like one the plugin renders and {@code architecture-tier0.yml}
 * reads it back ({@code requestOf}). The kinds, their labels, their required fields and the status moves are the
 * bundle's ({@code manifest.support}), never redefined here. The text rules are those of {@link Escalate}.
 */
public final class Tier0 {

	public static final String LABEL = "architecture-tier0";

	/** The id a request carries until the architecture repository writes its record. */
	public static final String UNNUMBERED = "T0-?";

	/** The marker {@code requestOf} ends the last section at. */
	static final String MARKER = "<!-- sa-tier0 -->";

	public static final String WONT_FIX = "Won't Fix";

	public static final List<String> DRIVERS = List.of("Strategy", "Architecture", "Talent", "Process");

	private static final Pattern TDR_ID = Pattern.compile("^TDR-\\d+$");

	private static final Pattern ADR_ID = Pattern.compile("^ADR-\\d+$");

	private Tier0() {
	}

	public record Evidence(String where, String note) {
	}

	/** What {@code tier0.js render} takes: the request and the fields of its kind, in the order they were given. */
	public record Request(String kind, String title, String request, List<String> components, String requestedBy,
			List<Evidence> evidence, Map<String, Object> fields) {

		public Request {
			kind = kind == null ? "" : Escalate.trim(kind);
			title = title == null ? null : Escalate.trim(lf(title));
			request = lf(request);
			components = Escalate.list(components);
			requestedBy = requestedBy == null ? "" : Escalate.trim(lf(requestedBy));
			evidence = evidence == null ? List.of()
					: evidence.stream()
						.filter(Objects::nonNull)
						.map((e) -> new Evidence(lf(e.where()), lf(e.note())))
						.toList();
			fields = fields == null ? Map.of() : fields;
		}

		public Request withComponents(List<String> c) {
			return new Request(kind, title, request, c, requestedBy, evidence, fields);
		}

		public String text(String key) {
			Object v = fields.get(key);
			return v instanceof String s ? Escalate.trim(lf(s)) : v == null || v instanceof List<?> || v instanceof Map<?, ?> ? "" : String.valueOf(v);
		}

		public List<String> texts(String key) {
			return fields.get(key) instanceof List<?> l
					? Escalate.list(l.stream().filter((x) -> x instanceof String).map((x) -> (String) x).toList()) : List.of();
		}

	}

	/** The kinds of {@code support.tier0_kinds}: label, required fields and whether evidence is required. */
	public record Kind(String label, List<String> required, boolean evidence) {
	}

	/** {@code support.tier0_kinds} and {@code support.tdr_moves}, as the bundle lists them. */
	public record Policy(Map<String, Kind> kinds, Map<String, List<String>> moves) {

		@SuppressWarnings("unchecked")
		public static Policy of(Map<String, Object> support) {
			Map<String, Kind> kinds = new LinkedHashMap<>();
			J.map(support, "tier0_kinds").forEach((k, v) -> {
				Map<String, Object> m = v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
				Object ev = m.get("evidence");
				boolean evidence = Boolean.TRUE.equals(ev) || ev instanceof String s && !s.isBlank() && !"false".equals(s)
						&& !"none".equals(s) || ev instanceof List<?> l && !l.isEmpty();
				String label = J.str(m, "label");
				kinds.put(k, new Kind(label == null ? k : label, J.strings(m, "required"), evidence));
			});
			Map<String, List<String>> moves = new LinkedHashMap<>();
			J.map(support, "tdr_moves").forEach((k, v) -> moves.put(k, v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of()));
			return new Policy(kinds, moves);
		}

	}

	/** What is in the bundle's ledger that a request names. */
	public record Ledger(Map<String, String> tdrStatus, java.util.Set<String> adrs) {
	}

	/** The checks of the request against the release; an empty list when it can be filed. */
	public static List<String> validate(Request r, Policy p, Ledger l) {
		List<String> errors = new ArrayList<>();
		if (r.title() == null || r.title().isEmpty()) {
			errors.add("title is required");
		}
		else if (r.title().contains("\n")) {
			errors.add("title must be one line");
		}
		if (r.request() == null || Escalate.trim(r.request()).isEmpty()) {
			errors.add("request (what the developer asked for, in their words) is required");
		}
		Kind kind = p.kinds().get(r.kind());
		if (kind == null) {
			errors.add("unknown kind \"" + r.kind() + "\" — one of " + String.join(", ", p.kinds().keySet()));
			return errors;
		}
		for (String f : kind.required()) {
			boolean listed = f.equals("solutions") || f.equals("consequences");
			if (listed ? r.texts(f).isEmpty() : r.text(f).isEmpty()) {
				errors.add("field \"" + f + "\" is required for " + r.kind() + (listed ? " (a list)" : ""));
			}
		}
		if (kind.evidence() && r.evidence().stream().noneMatch((e) -> !blank(e.where()))) {
			errors.add(r.kind() + " needs evidence: the service repository, commit and file:line, in where");
		}
		switch (r.kind()) {
			case "tdr-workaround" -> {
				String driver = r.text("driver");
				if (!driver.isEmpty() && !DRIVERS.contains(driver)) {
					errors.add("driver must be " + String.join(" · ", DRIVERS));
				}
				for (String a : r.texts("adrs")) {
					if (!l.adrs().contains(a)) {
						errors.add(a + " is not an ADR of the architecture");
					}
				}
			}
			case "tdr-status" -> {
				String tdr = r.text("tdr");
				String to = r.text("to").replace('’', '\'');
				if (!tdr.isEmpty() && !TDR_ID.matcher(tdr).matches()) {
					errors.add("tdr must be a TDR id (TDR-NNN), not \"" + tdr + "\"");
				}
				else if (!tdr.isEmpty() && !l.tdrStatus().containsKey(tdr)) {
					errors.add(tdr + " is not a TDR of the architecture");
				}
				else if (!tdr.isEmpty() && !to.isEmpty()) {
					String current = l.tdrStatus().get(tdr);
					if (WONT_FIX.equals(to)) {
						errors.add("a move to Won't Fix is the architect's decision: it is never requested here");
					}
					else if (!p.moves().getOrDefault(current, List.of()).contains(to)) {
						List<String> allowed = p.moves().getOrDefault(current, List.of()).stream().filter((x) -> !WONT_FIX.equals(x)).toList();
						errors.add(tdr + " is " + current + ": " + current + " → " + to + " is not a move"
								+ (allowed.isEmpty() ? " (none is allowed from " + current + ")" : " — one of " + String.join(", ", allowed)));
					}
				}
				if ("Planned".equals(to) && r.text("target_date").isEmpty()) {
					errors.add("field \"target_date\" is required when the move is to Planned");
				}
			}
			case "doc-fix" -> {
				String file = r.text("file");
				if (!file.isEmpty() && (!file.startsWith("docs/") || file.contains(".."))) {
					errors.add("file must be under docs/, not \"" + file + "\"");
				}
				String before = r.text("before");
				String after = r.text("after");
				if (before.contains("\n") || after.contains("\n")) {
					errors.add("before and after are one line each");
				}
				if (!before.isEmpty() && before.equals(after)) {
					errors.add("before and after must differ");
				}
			}
			case "adr-proposed" -> {
			}
			default -> {
			}
		}
		return errors;
	}

	public static Escalate.Issue issue(Request r, Policy p, String architecture) {
		List<String> labels = List.of(LABEL, "tier0:" + r.kind());
		return new Escalate.Issue("[" + UNNUMBERED + "] " + r.title(), labels, body(r, p, architecture));
	}

	static String body(Request r, Policy p, String architecture) {
		Kind kind = p.kinds().get(r.kind());
		String label = kind == null ? "" : kind.label();
		Map<String, String> facts = new LinkedHashMap<>();
		facts.put("Architecture", or(architecture));
		facts.put("Kind", "`" + r.kind() + "`" + (label.isEmpty() ? "" : " — " + label));
		facts.put("Requested by", or(r.requestedBy()));
		facts.put("Components", or(String.join(", ", r.components())));
		List<String> w = new ArrayList<>();
		w.add("| | |");
		w.add("|---|---|");
		facts.forEach((k, v) -> w.add("| " + k + " | " + Escalate.cell(v) + " |"));
		w.add("");
		String req0 = Escalate.trim(r.request());
		w.addAll(List.of("## Request", "", fence(req0) + "text", req0, fence(req0), ""));
		w.addAll(List.of("## Evidence", ""));
		List<String> ev = new ArrayList<>();
		for (Evidence e : r.evidence()) {
			List<String> parts = new ArrayList<>();
			if (!blank(e.where())) {
				parts.add(Escalate.trim(e.where()));
			}
			if (!blank(e.note())) {
				parts.add(Escalate.trim(e.note()));
			}
			if (!parts.isEmpty()) {
				ev.add("- " + Escalate.oneLine(String.join(" — ", parts)));
			}
		}
		w.addAll(ev.isEmpty() ? List.of("- None.") : ev);
		String json = stringify(r.fields(), "");
		w.addAll(List.of("", "## Fields", "", fence(json) + "json", json, fence(json), ""));
		w.add("_Tier 0: the architecture repository writes it once the issue carries the label `" + LABEL
				+ "` (architecture-tier0.yml); a request that is no Tier 0 write is refused there, with the reason._");
		w.add(MARKER);
		w.add("");
		return String.join("\n", w);
	}

	/** {@code JSON.stringify(value, null, 2)}: two-space indentation, a member or an item to a line, the key order kept. */
	static String stringify(Object v, String indent) {
		String in = indent + "  ";
		if (v instanceof Map<?, ?> m) {
			if (m.isEmpty()) {
				return "{}";
			}
			List<String> rows = new ArrayList<>();
			m.forEach((k, x) -> rows.add(in + J.write(String.valueOf(k)) + ": " + stringify(x, in)));
			return "{\n" + String.join(",\n", rows) + "\n" + indent + "}";
		}
		if (v instanceof List<?> l) {
			if (l.isEmpty()) {
				return "[]";
			}
			return "[\n" + String.join(",\n", l.stream().map((x) -> in + stringify(x, in)).toList()) + "\n" + indent + "]";
		}
		return J.write(v);
	}

	/** A fence longer than any run of backticks in the text, and at least three. */
	private static String fence(String text) {
		int longest = 2;
		Matcher m = Pattern.compile("`+").matcher(text);
		while (m.find()) {
			longest = Math.max(longest, m.group().length());
		}
		return "`".repeat(longest + 1);
	}

	private static boolean blank(String s) {
		return s == null || Escalate.trim(s).isEmpty();
	}

	private static String lf(String s) {
		return s == null ? null : s.replaceAll("\r\n?", "\n");
	}

	private static String or(String s) {
		return s == null || s.isEmpty() ? "—" : s;
	}

}
