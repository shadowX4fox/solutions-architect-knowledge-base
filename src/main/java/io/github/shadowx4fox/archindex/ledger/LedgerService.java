package io.github.shadowx4fox.archindex.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.changes.BundleDiff;
import io.github.shadowx4fox.archindex.index.Indexer;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * Exact answers from the facts ledger the plugin collected ({@code ledger.json}): components, integrations, flows and
 * their budgets, ADRs and TDRs. Lists and relations come from here, complete, never from search.
 */
public class LedgerService {

	private final Bundles bundles;

	private final Indexer indexer;

	public LedgerService(Bundles bundles, Indexer indexer) {
		this.bundles = bundles;
		this.indexer = indexer;
	}

	public List<Map<String, Object>> projects() {
		List<Map<String, Object>> out = new ArrayList<>();
		bundles.store().projects().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach((e) -> {
			BundleStore.Project p = e.getValue();
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("project", e.getKey());
			m.put("name", p.latest().or(p::draft).map(Bundle::projectName).orElse(e.getKey()));
			m.put("latest", p.latest().map(Bundle::version).orElse(null));
			m.put("released", List.copyOf(p.released().keySet()));
			p.draft().ifPresent((d) -> m.put("draft", d.version()));
			p.latest().ifPresent((b) -> {
				m.put("language", b.language());
				m.put("build_packs", List.copyOf(b.packs().keySet()));
				Indexer.SlotState s = indexer.state(e.getKey(), Indexer.slot(b));
				m.put("search", s == null ? "not indexed" : s.status().name().toLowerCase(Locale.ROOT));
			});
			out.add(m);
		});
		return out;
	}

	public Map<String, Object> architecture(String project, String version) {
		Bundle b = bundles.bundle(project, version);
		Map<String, Object> a = J.map(b.ledger(), "architecture");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("name", b.projectName());
		m.put("version", b.version());
		m.put("channel", b.channel());
		m.put("status", a.get("status"));
		m.put("released", a.get("released"));
		m.put("language", b.language());
		m.put("type", a.get("type"));
		m.put("systems", J.maps(b.ledger(), "systems").stream().map((s) -> pick(s, "name", "slug", "type", "technology")).toList());
		m.put("components", J.maps(b.ledger(), "components").stream().map(LedgerService::componentRow).toList());
		m.put("counts", J.map(b.manifest(), "counts"));
		m.put("build_packs", List.copyOf(b.packs().keySet()));
		m.put("no_build_pack", b.skipped());
		m.put("git", J.map(b.manifest(), "git"));
		Map<String, Object> support = J.map(b.manifest(), "support");
		m.put("support", support.isEmpty() ? null : support);
		return m;
	}

	static Map<String, Object> componentRow(Map<String, Object> c) {
		return pick(c, "slug", "name", "internal_name", "level", "system", "type", "technology", "description", "component_version");
	}

	public Map<String, Object> component(String project, String ref, String version) {
		Bundle b = bundles.bundle(project, version);
		Map<String, Object> c = bundles.component(b, ref);
		String slug = J.str(c, "slug");
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("version", b.version());
		m.putAll(pick(c, "slug", "name", "internal_name", "level", "system", "type", "technology", "description",
				"component_version", "last_updated", "file", "key_concepts", "scope", "related_adrs", "capacity", "delivery"));
		m.put("integrations", integrationsOf(b, c));
		m.put("flows", BundleDiff.flowsOf(b.ledger(), slug));
		m.put("build_pack", b.packs().containsKey(slug) ? "available — get_build_pack" : b.skipped()
			.stream()
			.filter((s) -> slug.equals(J.str(s, "component")))
			.map((s) -> "none: " + J.str(s, "reason"))
			.findFirst()
			.orElse("none"));
		return m;
	}

	/** The docs/05 rows that name the component, as written: From and To are the document's, never a guessed direction. */
	static List<Map<String, Object>> integrationsOf(Bundle b, Map<String, Object> c) {
		String name = J.str(c, "name");
		return J.maps(b.ledger(), "integrations")
			.stream()
			.filter((i) -> Objects.equals(name, J.str(i, "from")) || Objects.equals(name, J.str(i, "to")))
			.map((i) -> pick(i, "id", "from", "to", "protocol", "pattern", "boundary", "retired"))
			.toList();
	}

	public Map<String, Object> integrations(String project, String component, String version) {
		Bundle b = bundles.bundle(project, version);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("version", b.version());
		m.put("note", "From and To are as docs/05 states them; a row does not say which side depends on the other");
		m.put("integrations", bundles.componentIfAny(b, component)
			.map((c) -> integrationsOf(b, c))
			.orElseGet(() -> J.maps(b.ledger(), "integrations").stream().map((i) -> pick(i, "id", "from", "to", "protocol", "pattern", "boundary", "retired")).toList()));
		return m;
	}

	public Map<String, Object> flows(String project, String component, String version) {
		Bundle b = bundles.bundle(project, version);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("version", b.version());
		m.put("flows", bundles.componentIfAny(b, component)
			.map((c) -> BundleDiff.flowsOf(b.ledger(), J.str(c, "slug")))
			.orElseGet(() -> J.maps(b.ledger(), "flows").stream().map((f) -> flow(b, f)).toList()));
		return m;
	}

	public Map<String, Object> flow(String project, String code, String version) {
		Bundle b = bundles.bundle(project, version);
		String want = code == null ? "" : code.trim();
		for (Map<String, Object> f : J.maps(b.ledger(), "flows")) {
			if (want.equalsIgnoreCase(J.str(f, "code")) || want.equalsIgnoreCase(J.str(f, "name"))
					|| J.strings(f, "use_cases").stream().anyMatch(want::equalsIgnoreCase)) {
				return flow(b, f);
			}
		}
		throw new ToolException("no flow '" + code + "'; flows: " + String.join(", ",
				J.maps(b.ledger(), "flows").stream().map((f) -> J.str(f, "code") + ". " + J.str(f, "name")).toList()));
	}

	private static Map<String, Object> flow(Bundle b, Map<String, Object> f) {
		Map<String, Object> m = pick(f, "code", "name", "trigger", "use_cases", "latency_target", "governed_by");
		List<Map<String, Object>> phases = new ArrayList<>();
		for (String n : J.strings(f, "phases")) {
			J.maps(b.ledger(), "phases")
				.stream()
				.filter((p) -> n.equals(J.str(p, "n")))
				.findFirst()
				.ifPresentOrElse((p) -> phases.add(pick(p, "n", "name", "hops", "latency_budget", "critical", "failure_mode")),
						() -> phases.add(Map.of("n", n, "name", "Not documented")));
		}
		m.put("phases", phases);
		return m;
	}

	public Map<String, Object> decisions(String project, String kind, String status, String version) {
		Bundle b = bundles.bundle(project, version);
		String k = kind == null || kind.isBlank() ? "adr" : kind.trim().toLowerCase(Locale.ROOT);
		if (!k.equals("adr") && !k.equals("tdr")) {
			throw new ToolException("kind is adr or tdr");
		}
		List<Map<String, Object>> rows = J.maps(b.ledger(), k + "s")
			.stream()
			.filter((r) -> status == null || status.isBlank() || status.equalsIgnoreCase(J.str(r, "status")))
			.map((r) -> pick(r, "id", "title", "status", "scope", "file", "initiative", "driver"))
			.toList();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("project", b.projectKey());
		m.put("version", b.version());
		m.put(k + "s", rows);
		return m;
	}

	public Map<String, Object> chunk(String project, String chunkId, String version) {
		Bundle b = bundles.bundle(project, version);
		return b.chunk(chunkId)
			.map((c) -> io.github.shadowx4fox.archindex.index.SearchService.hit(b, c, null, 0))
			.orElseThrow(() -> new ToolException("no chunk " + chunkId + " in " + b.projectKey() + " " + b.version()));
	}

	static Map<String, Object> pick(Map<String, Object> src, String... keys) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (String k : keys) {
			if (src.get(k) != null) {
				m.put(k, src.get(k));
			}
		}
		return m;
	}

}
