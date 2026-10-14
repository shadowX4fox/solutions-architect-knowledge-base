package io.github.shadowx4fox.archindex.changes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.bundle.Semver;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * {@code what_changed}: what a release changed for one container, so a developer knows what to update in the service
 * and in its {@code .architecture.json}. The pair an export shipped as {@code changes.json} is read as is; any other
 * pair the server holds is compared the same way ({@link BundleDiff}).
 */
public class ChangesService {

	private final Bundles bundles;

	public ChangesService(Bundles bundles) {
		this.bundles = bundles;
	}

	public Map<String, Object> whatChanged(String project, String component, String from, String to) {
		if (from == null || from.isBlank()) {
			throw new ToolException("name the version you built against (from) — it is in .architecture.json");
		}
		Bundle n = bundles.bundle(project, to);
		Bundle o = bundles.bundle(project, from);
		if (Semver.valid(o.version()) && Semver.valid(n.version()) && Semver.compare(o.version(), n.version()) > 0) {
			throw new ToolException("from " + o.version() + " is newer than to " + n.version());
		}
		String slug = component == null || component.isBlank() ? null : slugIn(n, o, component);
		Map<String, Object> d;
		String source;
		Map<String, Object> shipped = n.changes().orElse(null);
		if (shipped != null && o.version().equals(J.str(shipped, "from")) && n.released() && o.released()) {
			d = new LinkedHashMap<>(shipped);
			if (slug != null) {
				List<Map<String, Object>> one = J.maps(shipped, "components").stream().filter((c) -> slug.equals(J.str(c, "component"))).toList();
				d.put("components", one.isEmpty() ? BundleDiff.diff(o, n, slug, false).get("components") : one);
			}
			source = "changes.json of " + n.version();
		}
		else {
			try {
				d = BundleDiff.diff(o, n, slug, false);
			}
			catch (IllegalArgumentException e) {
				throw new ToolException(e.getMessage());
			}
			source = "computed from the two bundles";
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", source);
		out.put("action_required", J.maps(d, "components").stream().filter((c) -> Boolean.TRUE.equals(c.get("action_required"))).map((c) -> J.str(c, "component")).toList());
		out.put("summary", BundleDiff.render(d));
		out.put("changes", d);
		return out;
	}

	/** The component's slug from either version: a container removed in the newer one still has a history. */
	private String slugIn(Bundle n, Bundle o, String ref) {
		try {
			return J.str(bundles.component(n, ref), "slug");
		}
		catch (ToolException e) {
			return J.str(bundles.component(o, ref), "slug");
		}
	}

}
