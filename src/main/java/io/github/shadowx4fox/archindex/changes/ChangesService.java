package io.github.shadowx4fox.archindex.changes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.bundle.Semver;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * {@code what_changed}: what a release changed for one container, so a developer knows what to update in the service
 * and in its {@code .architecture.json}. The pair an export shipped as {@code changes.json} is read as is; any other
 * pair the server holds is compared the same way ({@link BundleDiff}). A pack replaced within a released version is
 * answered from the revisions the store recorded when it was replaced.
 */
public class ChangesService {

	/** BUILD.md's placeholders — {@code <pack_revision from get_build_pack>}, {@code <the pack_revision in this file>}: no revision recorded. */
	private static final Pattern PLACEHOLDER = Pattern.compile("^<[^>]*>$");

	private final Bundles bundles;

	public ChangesService(Bundles bundles) {
		this.bundles = bundles;
	}

	public Map<String, Object> whatChanged(String project, String component, String from, String to) {
		return whatChanged(project, component, from, null, to);
	}

	/**
	 * With {@code fromRevision} — the {@code pack_revision} a repository's {@code .architecture.json} records — the pack
	 * replacements of {@code from} made after that revision come first: alone when {@code to} is the same version, then
	 * the diff from {@code from} as held to {@code to}. Without it, the same version lists every replacement of it.
	 */
	public Map<String, Object> whatChanged(String project, String component, String from, String fromRevision, String to) {
		if (from == null || from.isBlank()) {
			throw new ToolException("name the version you built against (from) — it is in .architecture.json");
		}
		Bundle n = bundles.bundle(project, to);
		Bundle o = bundles.bundle(project, from);
		if (Semver.valid(o.version()) && Semver.valid(n.version()) && Semver.compare(o.version(), n.version()) > 0) {
			throw new ToolException("from " + o.version() + " is newer than to " + n.version());
		}
		String slug = component == null || component.isBlank() ? null : slugIn(n, o, component);
		boolean placeholder = fromRevision != null && PLACEHOLDER.matcher(fromRevision.trim()).matches();
		String rev = fromRevision == null || fromRevision.isBlank() || placeholder ? null : fromRevision.trim();
		if (rev != null && slug == null) {
			throw new ToolException("from_revision is the revision of one component's build pack — name that component");
		}
		boolean sameVersion = o.version().equals(n.version()) && o.released() == n.released();
		List<Map<String, Object>> replacements = o.released() && (sameVersion || rev != null) ? replacements(project, o, slug, rev) : List.of();
		Map<String, Object> out = new LinkedHashMap<>();
		if (sameVersion) {
			out.put("source", "the build packs of " + o.version() + " replaced since " + (rev == null ? "it was published" : "revision " + rev));
			out.put("action_required", actionRequired(replacements, null));
			out.put("summary", replacements.isEmpty()
					? "No build pack" + (slug == null ? "" : " of " + slug) + " of " + o.version() + " was replaced"
							+ (rev == null ? "" : " after revision " + rev.substring(0, Math.min(12, rev.length()))) + ".\n"
					: String.join("\n", replacements.stream().map((r) -> BundleDiff.render(J.map(r, "changes"))).toList()));
			out.put("replacements", replacements);
		}
		else {
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
			out.put("source", replacements.isEmpty() ? source : "the build packs of " + o.version() + " replaced since revision " + rev + ", then " + source);
			out.put("action_required", actionRequired(replacements, d));
			List<String> md = new ArrayList<>(replacements.stream().map((r) -> BundleDiff.render(J.map(r, "changes"))).toList());
			md.add(BundleDiff.render(d));
			out.put("summary", String.join("\n", md));
			if (rev != null) {
				out.put("replacements", replacements);
			}
			out.put("changes", d);
		}
		if (slug != null && n.pack(slug).isPresent()) {
			out.put("pack_revision", n.packRevision(slug).orElse(null));
		}
		if (placeholder) {
			out.put("note", "from_revision is BUILD.md's placeholder, not a revision: record the pack_revision get_build_pack returns in .architecture.json");
		}
		return out;
	}

	/**
	 * The replacements of released version {@code o} the server recorded, oldest first — every one, or with {@code rev}
	 * those after it. A revision the server never held for that pack is an error naming the ones it did.
	 */
	private List<Map<String, Object>> replacements(String project, Bundle o, String slug, String rev) {
		List<Map<String, Object>> all = bundles.store().find(project).map((p) -> p.revisions(o.version())).orElse(List.of());
		List<Map<String, Object>> mine = slug == null ? all : all.stream().filter((r) -> J.map(r, "packs").containsKey(slug)).toList();
		int start = 0;
		if (rev != null) {
			List<String> known = new ArrayList<>();
			mine.forEach((r) -> known.add(J.str(J.map(J.map(r, "packs"), slug), "from")));
			String current = o.packRevision(slug).orElse(null);
			if (current != null) {
				known.add(current);
			}
			start = known.indexOf(rev);
			if (start < 0) {
				throw new ToolException(known.isEmpty() ? "no build pack of " + slug + " in " + o.version() + " — no revision to compare"
						: "revision " + rev + " is not a revision of " + slug + "'s build pack in " + o.version()
								+ " — the server holds: " + String.join(", ", known));
			}
		}
		return mine.subList(start, mine.size()).stream().map((r) -> slug == null ? r : only(r, slug)).toList();
	}

	/** A replacement narrowed to one component: its pack's revisions and its row of the diff. */
	private static Map<String, Object> only(Map<String, Object> r, String slug) {
		Map<String, Object> out = new LinkedHashMap<>(r);
		out.put("packs", Map.of(slug, J.map(J.map(r, "packs"), slug)));
		Map<String, Object> d = new LinkedHashMap<>(J.map(r, "changes"));
		d.put("components", J.maps(d, "components").stream().filter((c) -> slug.equals(J.str(c, "component"))).toList());
		out.put("changes", d);
		return out;
	}

	private static List<String> actionRequired(List<Map<String, Object>> replacements, Map<String, Object> d) {
		Set<String> out = new TreeSet<>();
		List<Map<String, Object>> diffs = new ArrayList<>(replacements.stream().map((r) -> J.map(r, "changes")).toList());
		if (d != null) {
			diffs.add(d);
		}
		for (Map<String, Object> x : diffs) {
			J.maps(x, "components").stream().filter((c) -> Boolean.TRUE.equals(c.get("action_required"))).forEach((c) -> out.add(J.str(c, "component")));
		}
		return List.copyOf(out);
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
