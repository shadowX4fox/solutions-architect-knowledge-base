package io.github.shadowx4fox.archindex.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.J;

/** Resolves a tool's (project, version, component) to a bundle and a ledger fact, with errors an agent can act on. */
public class Bundles {

	private final BundleStore store;

	public Bundles(BundleStore store) {
		this.store = store;
	}

	public BundleStore store() {
		return store;
	}

	public Bundle bundle(String project, String version) {
		if (project == null || project.isBlank()) {
			throw new ToolException("name a project — list_projects lists the ones this server holds");
		}
		BundleStore.Project p = store.find(project)
			.orElseThrow(() -> new ToolException("no project '" + project + "' on this server; held: "
					+ String.join(", ", store.projects().keySet())));
		return store.resolve(project, version).orElseThrow(() -> new ToolException("project " + project
				+ " holds no version '" + (version == null ? "latest" : version) + "'; released: "
				+ String.join(", ", p.released().keySet()) + (p.draft().isPresent() ? "; and a draft ("
						+ p.draft().get().version() + ", ask for version \"draft\")" : "")));
	}

	/**
	 * A component by its slug, its canonical name or its internal name — exact, ignoring case. Never a partial match:
	 * a name the ledger does not hold is an error that lists the names it does.
	 */
	public Map<String, Object> component(Bundle b, String ref) {
		if (ref == null || ref.isBlank()) {
			throw new ToolException("name a component (slug, canonical name or internal name)");
		}
		String r = ref.trim().toLowerCase(Locale.ROOT);
		for (Map<String, Object> c : J.maps(b.ledger(), "components")) {
			for (String k : new String[] { J.str(c, "slug"), J.str(c, "name"), J.str(c, "internal_name") }) {
				if (k != null && k.toLowerCase(Locale.ROOT).equals(r)) {
					return c;
				}
			}
		}
		List<String> names = J.maps(b.ledger(), "components")
			.stream()
			.map((c) -> J.str(c, "name") + " (" + J.str(c, "slug") + ")")
			.toList();
		throw new ToolException("no component '" + ref + "' in " + b.projectKey() + " " + b.version()
				+ "; components: " + String.join(", ", names));
	}

	public Optional<Map<String, Object>> componentIfAny(Bundle b, String ref) {
		return ref == null || ref.isBlank() ? Optional.empty() : Optional.of(component(b, ref));
	}

}
