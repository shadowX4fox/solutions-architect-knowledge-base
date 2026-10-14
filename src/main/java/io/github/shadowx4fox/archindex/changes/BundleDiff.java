package io.github.shadowx4fox.archindex.changes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.Chunk;
import io.github.shadowx4fox.archindex.bundle.J;

/**
 * What changed for each container between two bundles of one project — the plugin's {@code diffBundles} and
 * {@code renderChanges} ({@code export-index.js}) in Java, field for field, so {@code what_changed} answers any pair of
 * versions the server holds and not only the pair an export shipped as {@code changes.json}. Hashes, the ledger and the
 * pack parts are compared; nothing is judged. The test suite holds this port to the plugin's own changes.json.
 */
public final class BundleDiff {

	private static final List<String> FACT_FIELDS = List.of("name", "type", "technology", "internal_name",
			"component_version", "system");

	private static final List<String> INTEGRATION_FIELDS = List.of("from", "to", "protocol", "pattern", "boundary",
			"retired");

	private static final Pattern HOP_SPLIT = Pattern.compile("\\s*(?:→|->|,|;)\\s*");

	private BundleDiff() {
	}

	public static Map<String, Object> diff(Bundle o, Bundle n, String component, boolean all) {
		if (!Objects.equals(o.projectKey(), n.projectKey())) {
			throw new IllegalArgumentException("bundles of two projects: " + o.projectKey() + " and " + n.projectKey());
		}
		Set<String> slugs = new TreeSet<>();
		for (Bundle b : List.of(o, n)) {
			for (Map<String, Object> c : J.maps(b.ledger(), "components")) {
				if ("L2".equals(J.str(c, "level"))) {
					slugs.add(J.str(c, "slug"));
				}
			}
		}
		if (component != null && !slugs.contains(component)) {
			throw new IllegalArgumentException(component + " is a container of neither version");
		}
		List<Map<String, Object>> comps = new ArrayList<>();
		for (String s : component != null ? List.of(component) : List.copyOf(slugs)) {
			Map<String, Object> r = componentChanges(o, n, s);
			if (all || component != null || Boolean.TRUE.equals(r.get("changed"))) {
				comps.add(r);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("schema", Bundle.SCHEMA + "#changes");
		out.put("project", n.projectKey());
		out.put("from", o.version());
		out.put("to", n.version());
		out.put("components", comps);
		return out;
	}

	private record SetDiff(List<String> added, List<String> removed, List<String> changed) {

		boolean any() {
			return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
		}

		Map<String, Object> json() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("added", added);
			m.put("removed", removed);
			m.put("changed", changed);
			return m;
		}

	}

	private static <T> SetDiff setDiff(List<T> a, List<T> b, Function<T, String> key, BiPredicate<T, T> same) {
		Map<String, T> am = new LinkedHashMap<>();
		a.forEach((x) -> am.put(key.apply(x), x));
		Map<String, T> bm = new LinkedHashMap<>();
		b.forEach((x) -> bm.put(key.apply(x), x));
		List<String> added = bm.keySet().stream().filter((k) -> !am.containsKey(k)).toList();
		List<String> removed = am.keySet().stream().filter((k) -> !bm.containsKey(k)).toList();
		List<String> changed = bm.keySet()
			.stream()
			.filter((k) -> am.containsKey(k) && !same.test(am.get(k), bm.get(k)))
			.toList();
		return new SetDiff(added, removed, changed);
	}

	static String plain(String s) {
		if (s == null) {
			return "";
		}
		return s.replaceAll("<!--[\\s\\S]*?-->", "")
			.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
			.replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
			.replaceAll("`([^`]*)`", "$1")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static Function<String, String> resolver(Map<String, Object> ledger) {
		Map<String, String> by = new HashMap<>();
		for (Map<String, Object> c : J.maps(ledger, "components")) {
			for (String k : new String[] { J.str(c, "name"), J.str(c, "slug"), J.str(c, "internal_name") }) {
				if (k != null && !k.isEmpty()) {
					by.put(k.trim(), J.str(c, "slug"));
				}
			}
		}
		return (s) -> by.get(plain(s));
	}

	private static List<String> hopNames(Object hops) {
		List<String> out = new ArrayList<>();
		for (String h : HOP_SPLIT.split(hops == null ? "" : String.valueOf(hops))) {
			String p = plain(h);
			if (!p.isEmpty()) {
				out.add(p);
			}
		}
		return out;
	}

	/** The flows a container takes part in, from a ledger: the phases whose Hops name it, the flows that run them. */
	public static List<Map<String, Object>> flowsOf(Map<String, Object> ledger, String slug) {
		Function<String, String> slugOf = resolver(ledger);
		List<Map<String, Object>> mine = J.maps(ledger, "phases")
			.stream()
			.filter((p) -> hopNames(p.get("hops")).stream().map(slugOf).anyMatch(slug::equals))
			.toList();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> f : J.maps(ledger, "flows")) {
			List<String> ns = J.strings(f, "phases");
			if (ns.stream().noneMatch((x) -> mine.stream().anyMatch((p) -> x.equals(J.str(p, "n"))))) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", J.str(f, "code"));
			m.put("name", J.str(f, "name"));
			m.put("use_cases", f.get("use_cases"));
			m.put("latency_target", f.get("latency_target"));
			m.put("governed_by", f.get("governed_by") == null ? List.of() : f.get("governed_by"));
			List<Map<String, Object>> phases = new ArrayList<>();
			for (String x : ns) {
				mine.stream().filter((p) -> x.equals(J.str(p, "n"))).findFirst().ifPresent((p) -> {
					Map<String, Object> ph = new LinkedHashMap<>();
					ph.put("n", J.str(p, "n"));
					ph.put("name", p.get("name"));
					ph.put("hops", p.get("hops"));
					ph.put("latency_budget", p.get("latency_budget"));
					phases.add(ph);
				});
			}
			m.put("phases", phases);
			out.add(m);
		}
		return out;
	}

	private static Map<String, Object> find(List<Map<String, Object>> l, String field, String value) {
		return l.stream().filter((x) -> Objects.equals(J.str(x, field), value)).findFirst().orElse(null);
	}

	private static List<Map<String, Object>> parts(Map<String, Object> pack, String kind) {
		return pack == null ? List.of() : J.maps(pack, "parts").stream().filter((x) -> kind.equals(J.str(x, "kind"))).toList();
	}

	private static Map<String, Object> fieldChange(String field, Object from, Object to) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("field", field);
		m.put("from", from);
		m.put("to", to);
		return m;
	}

	private static Map<String, Object> componentChanges(Bundle o, Bundle n, String slug) {
		Map<String, Object> oc = find(J.maps(o.ledger(), "components"), "slug", slug);
		Map<String, Object> nc = find(J.maps(n.ledger(), "components"), "slug", slug);
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("component", slug);
		r.put("name", J.str(nc != null ? nc : oc, "name"));
		String status = oc == null ? "added" : nc == null ? "removed" : "present";
		r.put("status", status);
		List<Map<String, Object>> facts = new ArrayList<>();
		if (oc != null && nc != null) {
			for (String k : FACT_FIELDS) {
				if (!J.same(oc.get(k), nc.get(k))) {
					facts.add(fieldChange(k, oc.get(k), nc.get(k)));
				}
			}
		}
		r.put("facts", facts);
		Map<String, Object> op = o.pack(slug).orElse(null);
		Map<String, Object> np = n.pack(slug).orElse(null);
		SetDiff contracts = null;
		SetDiff interfaces = null;
		SetDiff adrs = null;
		List<String> documents = new ArrayList<>();
		Map<String, Object> openQuestions = null;
		if (op != null || np != null) {
			BiPredicate<Map<String, Object>, Map<String, Object>> byHash = (a, b) -> Objects.equals(J.str(a, "sha256"), J.str(b, "sha256"));
			contracts = setDiff(parts(op, "asset"), parts(np, "asset"), (x) -> J.str(x, "title"), byHash);
			interfaces = setDiff(parts(op, "interface"), parts(np, "interface"), (x) -> J.str(x, "id").replaceFirst("^interface:", ""), byHash);
			adrs = setDiff(parts(op, "adr"), parts(np, "adr"), (x) -> J.str(x, "id").replaceFirst("^adr:", ""), byHash);
			for (String[] d : new String[][] { { "spec", "Component Specification" }, { "cross-cutting-standards", "Cross-Cutting Standards" }, { "runtime-view", "System Runtime View" } }) {
				Map<String, Object> a = op == null ? null : find(J.maps(op, "parts"), "id", d[0]);
				Map<String, Object> b = np == null ? null : find(J.maps(np, "parts"), "id", d[0]);
				if (a != null && b != null && !Objects.equals(J.str(a, "sha256"), J.str(b, "sha256"))) {
					documents.add(d[1]);
				}
			}
			List<Map<String, Object>> oq = op == null ? List.of() : J.maps(op, "open_questions");
			List<Map<String, Object>> nq = np == null ? List.of() : J.maps(np, "open_questions");
			Function<Map<String, Object>, String> qk = (x) -> J.str(x, "part") + "\u0000" + J.str(x, "text");
			Set<String> ok = new LinkedHashSet<>(oq.stream().map(qk).toList());
			Set<String> nk = new LinkedHashSet<>(nq.stream().map(qk).toList());
			openQuestions = new LinkedHashMap<>();
			openQuestions.put("new", nq.stream().filter((x) -> !ok.contains(qk.apply(x))).toList());
			openQuestions.put("resolved", oq.stream().filter((x) -> !nk.contains(qk.apply(x))).toList());
		}
		r.put("contracts", contracts == null ? null : contracts.json());
		r.put("interfaces", interfaces == null ? null : interfaces.json());
		r.put("adrs", adrs == null ? null : adrs.json());
		r.put("documents", documents);
		// the flows it takes part in, from each version's ledger — a budget moves with or without a build pack
		List<Map<String, Object>> flows = new ArrayList<>();
		List<Map<String, Object>> oldFlows = flowsOf(o.ledger(), slug);
		List<Map<String, Object>> newFlows = flowsOf(n.ledger(), slug);
		for (Map<String, Object> f : newFlows) {
			Map<String, Object> b = find(oldFlows, "code", J.str(f, "code"));
			if (b == null) {
				flows.add(flowChange(f, "joined", null));
				continue;
			}
			List<Map<String, Object>> d = new ArrayList<>();
			if (!J.same(b.get("latency_target"), f.get("latency_target"))) {
				d.add(fieldChange("latency_target", b.get("latency_target"), f.get("latency_target")));
			}
			List<Map<String, Object>> bp = J.maps(b, "phases");
			List<Map<String, Object>> fp = J.maps(f, "phases");
			for (Map<String, Object> p : fp) {
				Map<String, Object> q = find(bp, "n", J.str(p, "n"));
				if (q == null) {
					d.add(fieldChange("phase " + J.str(p, "n"), null, p.get("name")));
				}
				else {
					if (!J.same(q.get("latency_budget"), p.get("latency_budget"))) {
						d.add(fieldChange("phase " + J.str(p, "n") + " latency_budget", q.get("latency_budget"), p.get("latency_budget")));
					}
					if (!J.same(q.get("hops"), p.get("hops"))) {
						d.add(fieldChange("phase " + J.str(p, "n") + " hops", q.get("hops"), p.get("hops")));
					}
				}
			}
			for (Map<String, Object> q : bp) {
				if (find(fp, "n", J.str(q, "n")) == null) {
					d.add(fieldChange("phase " + J.str(q, "n"), q.get("name"), null));
				}
			}
			if (!d.isEmpty()) {
				flows.add(flowChange(f, "changed", d));
			}
		}
		for (Map<String, Object> f : oldFlows) {
			if (find(newFlows, "code", J.str(f, "code")) == null) {
				flows.add(flowChange(f, "left", null));
			}
		}
		r.put("flows", flows);
		Set<String> names = new LinkedHashSet<>();
		if (oc != null && J.str(oc, "name") != null) {
			names.add(J.str(oc, "name"));
		}
		if (nc != null && J.str(nc, "name") != null) {
			names.add(J.str(nc, "name"));
		}
		Function<Bundle, List<Map<String, Object>>> ints = (b) -> J.maps(b.ledger(), "integrations")
			.stream()
			.filter((i) -> names.contains(J.str(i, "from")) || names.contains(J.str(i, "to")))
			.toList();
		List<Map<String, Object>> oi = ints.apply(o);
		List<Map<String, Object>> ni = ints.apply(n);
		SetDiff integrations = setDiff(oi, ni, (i) -> J.str(i, "id"),
				(a, b) -> INTEGRATION_FIELDS.stream().allMatch((k) -> J.same(a.get(k), b.get(k))));
		Map<String, Object> ij = integrations.json();
		List<Map<String, Object>> fields = new ArrayList<>();
		for (String id : integrations.changed()) {
			Map<String, Object> a = find(oi, "id", id);
			Map<String, Object> b = find(ni, "id", id);
			for (String k : INTEGRATION_FIELDS) {
				if (!J.same(a.get(k), b.get(k))) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("integration", id);
					m.put("field", k);
					m.put("from", a.get(k));
					m.put("to", b.get(k));
					fields.add(m);
				}
			}
		}
		ij.put("fields", fields);
		r.put("integrations", ij);
		Function<Bundle, List<Chunk>> ch = (b) -> b.chunks().stream().filter((c) -> c.components().contains(slug)).toList();
		SetDiff sd = setDiff(ch.apply(o), ch.apply(n), Chunk::id, (a, b) -> Objects.equals(a.contentHash(), b.contentHash()));
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("added", sd.added().stream().map((id) -> where(n, id)).toList());
		sections.put("removed", sd.removed().stream().map((id) -> where(o, id)).toList());
		sections.put("changed", sd.changed().stream().map((id) -> where(n, id)).toList());
		r.put("sections", sections);
		r.put("open_questions", openQuestions);
		String pack = op != null && np != null ? "present" : np != null ? "added" : op != null ? "removed" : "absent";
		r.put("pack", pack);
		boolean oqMoved = openQuestions != null && (!((List<?>) openQuestions.get("new")).isEmpty() || !((List<?>) openQuestions.get("resolved")).isEmpty());
		boolean changed = !"present".equals(status) || !facts.isEmpty()
				|| java.util.stream.Stream.of(contracts, interfaces, adrs, integrations, sd).anyMatch((d) -> d != null && d.any())
				|| !documents.isEmpty() || !flows.isEmpty() || "added".equals(pack) || "removed".equals(pack) || oqMoved;
		r.put("changed", changed);
		// what the developer must act on: the code-facing parts moved. A component version alone is listed, not an action
		boolean action = "removed".equals(status)
				|| facts.stream().anyMatch((f) -> List.of("name", "type", "technology", "internal_name").contains(J.str(f, "field")))
				|| (contracts != null && contracts.any()) || (interfaces != null && interfaces.any()) || integrations.any()
				|| !flows.isEmpty() || documents.contains("Component Specification");
		r.put("action_required", action);
		return r;
	}

	private static Map<String, Object> flowChange(Map<String, Object> f, String change, List<Map<String, Object>> fields) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("flow", J.str(f, "code"));
		m.put("name", f.get("name"));
		m.put("change", change);
		if (fields != null) {
			m.put("fields", fields);
		}
		return m;
	}

	private static Map<String, Object> where(Bundle b, String id) {
		Chunk c = b.chunk(id).orElseThrow();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("file", c.file());
		m.put("title", c.title());
		m.put("kind", c.kind());
		return m;
	}

	/** The Markdown the plugin's {@code renderChanges} writes for the same diff. */
	public static String render(Map<String, Object> d) {
		List<String> l = new ArrayList<>();
		l.add("# What changed — " + J.str(d, "project") + " " + J.str(d, "from") + " → " + J.str(d, "to"));
		l.add("");
		List<Map<String, Object>> comps = J.maps(d, "components");
		if (comps.isEmpty()) {
			l.add("No container changed.");
		}
		for (Map<String, Object> c : comps) {
			l.add("## " + J.str(c, "name") + " (`" + J.str(c, "component") + "`)" + (Boolean.TRUE.equals(c.get("action_required")) ? " — action required" : ""));
			l.add("");
			if (!"present".equals(J.str(c, "status"))) {
				l.add("- the container was " + J.str(c, "status"));
			}
			for (Map<String, Object> f : J.maps(c, "facts")) {
				l.add("- " + J.str(f, "field") + ": " + J.write(f.get("from")) + " → " + J.write(f.get("to")));
			}
			String pack = J.str(c, "pack");
			if ("added".equals(pack) || "removed".equals(pack)) {
				l.add("- build pack " + pack);
			}
			list(l, "contracts", J.map(c, "contracts"));
			list(l, "interfaces", J.map(c, "interfaces"));
			list(l, "ADRs", J.map(c, "adrs"));
			list(l, "integrations", J.map(c, "integrations"));
			for (Map<String, Object> f : J.maps(J.map(c, "integrations"), "fields")) {
				l.add("  - " + J.str(f, "integration") + " " + J.str(f, "field") + ": " + dash(f.get("from")) + " → " + dash(f.get("to")));
			}
			List<String> docs = J.strings(c, "documents");
			if (!docs.isEmpty()) {
				l.add("- documents: " + String.join(", ", docs));
			}
			for (Map<String, Object> f : J.maps(c, "flows")) {
				StringBuilder s = new StringBuilder("- flow " + J.str(f, "flow") + ". " + J.str(f, "name") + ": " + J.str(f, "change"));
				List<Map<String, Object>> fs = J.maps(f, "fields");
				if (f.get("fields") != null) {
					s.append(" — ").append(String.join("; ", fs.stream().map((x) -> J.str(x, "field") + " " + dash(x.get("from")) + " → " + dash(x.get("to"))).toList()));
				}
				l.add(s.toString());
			}
			Map<String, Object> sec = J.map(c, "sections");
			List<Map<String, Object>> added = J.maps(sec, "added");
			List<Map<String, Object>> changed = J.maps(sec, "changed");
			List<Map<String, Object>> removed = J.maps(sec, "removed");
			if (!added.isEmpty() || !changed.isEmpty() || !removed.isEmpty()) {
				List<String> titles = new ArrayList<>();
				added.forEach((x) -> titles.add(J.str(x, "title")));
				changed.forEach((x) -> titles.add(J.str(x, "title")));
				l.add("- sections naming it: " + added.size() + " added, " + changed.size() + " changed, " + removed.size()
						+ " removed — " + String.join("; ", titles.subList(0, Math.min(8, titles.size()))));
			}
			Map<String, Object> oq = J.map(c, "open_questions");
			if (!oq.isEmpty()) {
				if (!J.list(oq, "new").isEmpty()) {
					l.add("- new open questions: " + J.list(oq, "new").size());
				}
				if (!J.list(oq, "resolved").isEmpty()) {
					l.add("- open questions resolved: " + J.list(oq, "resolved").size());
				}
			}
			l.add("");
		}
		return String.join("\n", l).stripTrailing() + "\n";
	}

	private static void list(List<String> l, String label, Map<String, Object> x) {
		List<String> a = J.strings(x, "added");
		List<String> r = J.strings(x, "removed");
		List<String> c = J.strings(x, "changed");
		if (a.isEmpty() && r.isEmpty() && c.isEmpty()) {
			return;
		}
		List<String> bits = new ArrayList<>();
		if (!a.isEmpty()) {
			bits.add("added " + String.join(", ", a));
		}
		if (!r.isEmpty()) {
			bits.add("removed " + String.join(", ", r));
		}
		if (!c.isEmpty()) {
			bits.add("changed " + String.join(", ", c));
		}
		l.add("- " + label + ": " + String.join(" · ", bits));
	}

	private static String dash(Object v) {
		return v == null ? "—" : String.valueOf(v);
	}

}
