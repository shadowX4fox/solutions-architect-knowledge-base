package io.github.shadowx4fox.archindex.escalation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plugin's escalation issue ({@code plugins/sa-records/scripts/shared/escalate.js}): {@code validate},
 * {@code render}, {@code issueTitle}, {@code labelsOf} and {@code issueBody}, field for field, so an issue the server
 * files reads like one the plugin files and {@code escalate.js import} reads it back ({@code requestOf}).
 * {@code EscalateTest} holds it to the plugin's own output. The trigger and route texts are the bundle's
 * ({@code manifest.support}), never redefined here.
 * <p>
 * The text rules are JavaScript's: {@code trim()} and {@code \s} take the ECMAScript white space (U+00A0 and U+FEFF
 * among it, U+0085 not), which neither {@code String.strip()} nor Java's {@code \s} does.
 */
public final class Escalate {

	/** The trigger that needs no evidence: low AI confidence is its own evidence. */
	public static final String LOW_CONFIDENCE = "low-confidence";

	public static final String LABEL = "architecture-escalation";

	/** The id an escalation filed by the server carries until {@code escalate.js import} numbers it. */
	public static final String UNNUMBERED = "ESC-?";

	/** ECMAScript WhiteSpace and LineTerminator: what {@code trim()} removes and {@code \s} matches. */
	private static final String WS = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";

	private static final Pattern EDGES = Pattern.compile("^[" + WS + "]+|[" + WS + "]+$");

	private static final Pattern BREAK = Pattern.compile("[" + WS + "]*\\n[" + WS + "]*");

	private static final Pattern NUMBERED = Pattern.compile("^ESC-\\d+$");

	private Escalate() {
	}

	public record Evidence(String trigger, String where, String note) {
	}

	/** The request {@code escalate.js new} takes ({@code request.json}). */
	public record Request(String title, String request, List<String> triggers, List<Evidence> evidence,
			List<String> proposal, List<String> questions, List<String> components, List<String> integrations,
			List<String> adrs, List<String> tdrs, String requestedBy, String route) {

		public Request {
			title = title == null ? null : trim(lf(title));
			request = lf(request);
			triggers = list(triggers);
			evidence = evidence == null ? List.of()
					: evidence.stream()
						.filter(Objects::nonNull)
						.map((e) -> new Evidence(lf(e.trigger()), lf(e.where()), lf(e.note())))
						.toList();
			proposal = lf(proposal);
			questions = lf(questions);
			components = list(components);
			integrations = list(integrations);
			adrs = list(adrs);
			tdrs = list(tdrs);
			requestedBy = requestedBy == null ? "" : trim(lf(requestedBy));
			route = route == null || trim(route).isEmpty() ? "tier-1" : trim(route);
		}

		public Request withComponents(List<String> c) {
			return new Request(title, request, triggers, evidence, proposal, questions, c, integrations, adrs, tdrs,
					requestedBy, route);
		}

	}

	/** The trigger ids with their texts and the route ids with theirs, as the bundle's {@code support} block lists them. */
	public record Policy(Map<String, String> triggers, Map<String, String> routes) {
	}

	public record Issue(String title, List<String> labels, String body) {
	}

	/** {@code validate()}: an empty list when the request can be filed. */
	public static List<String> validate(Request r, Policy p) {
		List<String> errors = new ArrayList<>();
		if (r.title() == null || trim(r.title()).isEmpty()) {
			errors.add("title is required");
		}
		else if (r.title().contains("\n")) {
			errors.add("title must be one line");
		}
		if (r.request() == null || trim(r.request()).isEmpty()) {
			errors.add("request (the RUN team's words, verbatim) is required");
		}
		List<String> t = r.triggers();
		if (t.isEmpty()) {
			errors.add("at least one trigger is required");
		}
		t.stream()
			.filter((x) -> !p.triggers().containsKey(x))
			.forEach((x) -> errors.add("unknown trigger \"" + x + "\" — one of " + String.join(", ", p.triggers().keySet())));
		r.evidence()
			.stream()
			.filter((e) -> e.trigger() != null && !e.trigger().isEmpty() && !t.contains(e.trigger()))
			.forEach((e) -> errors.add("evidence for \"" + e.trigger() + "\", which is not among the triggers"));
		t.stream()
			.filter((x) -> !LOW_CONFIDENCE.equals(x) && r.evidence().stream().noneMatch((e) -> x.equals(e.trigger())))
			.forEach((x) -> errors.add("trigger \"" + x + "\" has no evidence — a file and line, or the request's own words"));
		if (!p.routes().containsKey(r.route())) {
			errors.add("route must be " + String.join(" or ", p.routes().keySet()));
		}
		return errors;
	}

	/** {@code issueTitle}, {@code labelsOf} and {@code issueBody} of the record {@code create()} would write. */
	public static Issue issue(Request r, Policy p, String id, String architecture, String record) {
		List<String> labels = new ArrayList<>();
		labels.add(LABEL);
		labels.add(r.route());
		r.triggers().forEach((t) -> labels.add("trigger:" + t));
		return new Issue("[" + id + "] " + r.title(), labels, body(r, p, id, architecture, record));
	}

	static String body(Request r, Policy p, String id, String architecture, String record) {
		Map<String, String> facts = new LinkedHashMap<>();
		facts.put("Architecture", or(architecture));
		facts.put("Route", p.routes().getOrDefault(r.route(), r.route()));
		facts.put("Requested by", or(r.requestedBy()));
		facts.put("Components", or(String.join(", ", r.components())));
		facts.put("Integrations", or(String.join(", ", r.integrations())));
		List<String> ar = new ArrayList<>(r.adrs());
		ar.addAll(r.tdrs());
		facts.put("ADRs / TDRs", or(String.join(", ", ar)));
		facts.put("Record", record);
		List<String> w = new ArrayList<>();
		w.add("| | |");
		w.add("|---|---|");
		facts.forEach((k, v) -> w.add("| " + k + " | " + cell(v) + " |"));
		w.add("");
		w.add(trim(String.join("\n", sections(r, p))));
		w.add("");
		w.add(NUMBERED.matcher(id).matches()
				? "_Close it with `escalate.js close <project> " + id + " --by <ADR-NNN|TDR-NNN|architecture-vX.Y.Z>`._"
				: "_Import it into the architecture repository with `escalate.js import <project> <this issue>`; the import gives it its ESC number._");
		w.add("");
		return String.join("\n", w);
	}

	/** {@code render()} from {@code ## Request} up to {@code ## Resolution}, the part of the record the issue carries. */
	static List<String> sections(Request r, Policy p) {
		List<String> w = new ArrayList<>();
		String req0 = trim(r.request());
		int longest = 2;
		Matcher m = Pattern.compile("`+").matcher(req0);
		while (m.find()) {
			longest = Math.max(longest, m.group().length());
		}
		String fence = "`".repeat(longest + 1);
		w.addAll(List.of("## Request", "", fence + "text", req0, fence, ""));
		w.addAll(List.of("## Why it needs an architect", "", "| Trigger | Evidence |", "|---|---|"));
		for (String t : r.triggers()) {
			List<Evidence> ev = r.evidence().stream().filter((e) -> t.equals(e.trigger())).toList();
			if (ev.isEmpty()) {
				w.add("| " + t + " | " + cell(p.triggers().get(t)) + " |");
			}
			for (Evidence e : ev) {
				List<String> parts = new ArrayList<>();
				if (e.where() != null && !e.where().isEmpty()) {
					parts.add(e.where());
				}
				if (e.note() != null && !e.note().isEmpty()) {
					parts.add(e.note());
				}
				w.add("| " + t + " | " + cell(String.join(" — ", parts)) + " |");
			}
		}
		w.addAll(List.of("", "**Route**: " + p.routes().get(r.route()) + ".", ""));
		w.addAll(List.of("## What the AI would propose (not applied)", ""));
		w.addAll(r.proposal().isEmpty() ? List.of("- Nothing proposed.") : r.proposal().stream().map((l) -> "- " + oneLine(l)).toList());
		w.add("");
		w.addAll(List.of("## Open questions for the architect", ""));
		w.addAll(r.questions().isEmpty() ? List.of("- None.") : r.questions().stream().map((l) -> "- " + oneLine(l)).toList());
		w.add("");
		return w;
	}

	/** The issue body as Azure DevOps stores a work item's description ({@code htmlOf}). */
	public static String html(String markdown) {
		return "<pre>" + markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>";
	}

	// ---- the plugin's text helpers

	/** {@code String.prototype.trim()}. */
	public static String trim(String s) {
		return s == null ? "" : EDGES.matcher(s).replaceAll("");
	}

	/** {@code oneLine}: a proposal or question item is one bullet. */
	static String oneLine(String s) {
		return trim(BREAK.matcher(s).replaceAll(" "));
	}

	/** {@code cell}: a table cell holds no pipe and no line break. */
	static String cell(String s) {
		return (s == null ? "" : s).replace("|", "\\|").replace("\n", " ");
	}

	/** {@code list}: each item trimmed, the empty ones dropped. */
	static List<String> list(List<String> v) {
		return v == null ? List.of() : v.stream().filter(Objects::nonNull).map(Escalate::trim).filter((x) -> !x.isEmpty()).toList();
	}

	private static List<String> lf(List<String> v) {
		return list(v == null ? null : v.stream().filter(Objects::nonNull).map(Escalate::lf).toList());
	}

	/** The record file is read with its line endings as LF ({@code read}): the request is rendered from that text. */
	private static String lf(String s) {
		return s == null ? null : s.replaceAll("\r\n?", "\n");
	}

	private static String or(String s) {
		return s == null || s.isEmpty() ? "—" : s;
	}

}
