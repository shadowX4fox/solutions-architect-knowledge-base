package io.github.shadowx4fox.archindex.escalation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.shadowx4fox.archindex.ArchIndexProperties.Escalation;
import io.github.shadowx4fox.archindex.ArchIndexProperties.GitHost;
import io.github.shadowx4fox.archindex.ArchIndexProperties.Target;
import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * A RUN team's escalation, in the plugin's "ask once": {@link #preview} checks the request against the latest release's
 * support policy and returns the issue exactly as it would be opened — the plugin's rendering, {@code [ESC-?]} until
 * {@code escalate.js import} numbers it — with the commands that file it on the project's git host. The agent shows it,
 * and only after the user's yes files it with the developer's own access; then {@link #record} logs where it went. The
 * server writes nothing, calls no host and holds no token.
 * <p>
 * The {@code preview_id} is the issue's fingerprint, carried in the body as a comment: it finds a preview filed twice,
 * and it joins the two log lines on {@code archindex.escalation} — the preview (project, route, triggers, who asked)
 * and the filing (the URL) — which is the count of escalations per team, without a store.
 */
public class EscalationService {

	private static final Logger audit = LoggerFactory.getLogger("archindex.escalation");

	/** GitHub's limit on an issue body is 65,536 characters; the others are higher. */
	static final int MAX_BODY = 60_000;

	static final Pattern PREVIEW_ID = Pattern.compile("^[0-9a-f]{16}$");

	private final Bundles bundles;

	private final Escalation settings;

	public EscalationService(Bundles bundles, Escalation settings) {
		this.bundles = bundles;
		this.settings = settings;
	}

	/** Where this server files the project's escalations, if anywhere — for {@code get_architecture}. */
	public Map<String, Object> filing(String project) {
		Map<String, Object> m = new LinkedHashMap<>();
		settings.target(project).ifPresentOrElse((t) -> {
			m.put("enabled", true);
			m.put("host", t.hostName());
			m.put("repository", t.repository());
		}, () -> m.put("enabled", false));
		return m;
	}

	public Map<String, Object> preview(String project, Escalate.Request request) {
		Bundle b = bundles.bundle(project, null);
		Map<String, Object> support = policyOf(b);
		Target target = target(b.projectKey());
		Escalate.Policy policy = new Escalate.Policy(texts(support, "triggers"), texts(support, "routes"));
		List<String> warnings = new ArrayList<>();
		Escalate.Request req = request.withComponents(canonical(b, request.components(), warnings));
		check(b, "integrations", req.integrations(), "integration", warnings);
		check(b, "adrs", req.adrs(), "ADR", warnings);
		check(b, "tdrs", req.tdrs(), "TDR", warnings);
		List<String> errors = Escalate.validate(req, policy);
		if (!errors.isEmpty()) {
			throw new ToolException("the escalation cannot be filed as it is:\n- " + String.join("\n- ", errors));
		}
		String architecture = J.str(support, "released") != null ? J.str(support, "released")
				: J.str(J.map(b.manifest(), "git"), "tag");
		Escalate.Issue issue = Escalate.issue(req, policy, Escalate.UNNUMBERED, architecture, "—");
		String fingerprint = J.sha256(b.projectKey() + "\n" + issue.title() + "\n" + issue.body()).substring(0, 16);
		// after the body on its own line: requestOf() ends the last section at "\n<!--"
		String body = issue.body() + "<!-- sa-escalation: " + fingerprint + " -->\n";
		if (body.length() > MAX_BODY) {
			throw new ToolException("the issue body is " + body.length() + " characters, over " + MAX_BODY
					+ ": shorten the request, the evidence notes or the proposal");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("project", b.projectKey());
		out.put("version", b.version());
		out.put("preview_id", fingerprint);
		out.put("issue", Map.of("title", issue.title(), "labels", issue.labels(), "body", body));
		out.put("warnings", warnings);
		out.put("open_in_release", J.maps(support, "escalations_open"));
		out.put("file_with", FilingPlan.of(target, issue, body, fingerprint));
		out.put("next", "Nothing has been written. Show the user this issue as it stands — title, labels and body — with "
				+ "warnings and open_in_release (an escalation there that covers the same request is a duplicate: file "
				+ "nothing), and ask whether to file it. Only after the user says yes, follow file_with.steps.");
		audit.info("escalation preview project={} version={} route={} triggers={} requested_by=\"{}\" preview_id={}",
				b.projectKey(), b.version(), req.route(), String.join(",", req.triggers()), req.requestedBy(), fingerprint);
		return out;
	}

	/** The issue the agent filed for a preview: logged, so the escalations per team are counted. */
	public Map<String, Object> record(String project, String previewId, String url) {
		Target target = target(project);
		String id = previewId == null ? "" : previewId.strip();
		if (!PREVIEW_ID.matcher(id).matches()) {
			throw new ToolException("preview_id is the one preview_escalation returned (16 hex characters), not '" + previewId + "'");
		}
		String u = url == null ? "" : url.strip();
		if (!onTarget(target, u)) {
			throw new ToolException("'" + url + "' is not an issue of " + target.repository() + " on " + target.server()
					+ ", where " + project + " files its escalations");
		}
		audit.info("escalation filed project={} preview_id={} url={}", project, id, u);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", Escalate.UNNUMBERED);
		out.put("project", project);
		out.put("preview_id", id);
		out.put("url", u);
		out.put("next", "Filed for a human architect; the architecture is unchanged. The architect imports it with "
				+ "escalate.js import, which gives it its ESC number, and closes it with an ADR, a TDR or a release.");
		return out;
	}

	// ---- the policy and the checks

	/** The latest release's support block, when it lets a RUN team escalate. */
	static Map<String, Object> policyOf(Bundle b) {
		Map<String, Object> s = J.map(b.manifest(), "support");
		if (s.isEmpty()) {
			throw new ToolException(b.projectKey() + " " + b.version()
					+ " carries no support policy: it was exported before the plugin's v10.3.0 — re-export and publish it");
		}
		if (!"run".equals(J.str(s, "mode"))) {
			throw new ToolException(b.projectKey() + " is not declared a RUN-team project: escalations are for RUN teams. "
					+ "Changes go to the architect the usual way.");
		}
		if (!Boolean.TRUE.equals(s.get("tier0"))) {
			throw new ToolException(b.projectKey() + " has no architecture release yet: Tier 0 starts at the first release, "
					+ "and until then every change goes to Tier 1 (SA office hours) directly");
		}
		return s;
	}

	private Target target(String project) {
		return settings.target(project)
			.orElseThrow(() -> new ToolException("this server files no escalations for " + project
					+ ": its git host is not configured (archindex.escalation). Tell the user to raise it with the SA office "
					+ "hours directly, with the request, the triggers and their evidence. "
					+ "If the escalation is a question that blocks the build, hand the user the same questions, each with its "
					+ "build-pack part and line, for the architects."));
	}

	/** A URL on the target's host and repository (Azure DevOps: its organization, also as org.visualstudio.com). */
	static boolean onTarget(Target t, String url) {
		if (t.server() == null || t.repository() == null) {
			return false;
		}
		String u = url.toLowerCase(Locale.ROOT);
		String server = "https://" + t.server().toLowerCase(Locale.ROOT) + "/";
		String repo = t.repository().toLowerCase(Locale.ROOT);
		if (t.host() == GitHost.AZURE) {
			String org = repo.substring(0, Math.max(0, repo.indexOf('/')));
			return !org.isEmpty() && (u.startsWith(server + org + "/") || u.startsWith("https://" + org + ".visualstudio.com/"));
		}
		return u.startsWith(server + repo + "/");
	}

	private static Map<String, String> texts(Map<String, Object> support, String key) {
		Map<String, String> m = new LinkedHashMap<>();
		J.map(support, key).forEach((k, v) -> m.put(k, String.valueOf(v)));
		return m;
	}

	/** Components by their canonical names: matched as the ledger tools match one, a name it lacks kept and flagged. */
	private List<String> canonical(Bundle b, List<String> names, List<String> warnings) {
		List<String> out = new ArrayList<>();
		for (String n : names) {
			try {
				String name = J.str(bundles.component(b, n), "name");
				if (!name.equals(n)) {
					warnings.add("component \"" + n + "\" is filed under its canonical name \"" + name + "\"");
				}
				out.add(name);
			}
			catch (ToolException e) {
				warnings.add("component \"" + n + "\" is not a canonical name in the ledger of " + b.projectKey() + " "
						+ b.version());
				out.add(n);
			}
		}
		return out;
	}

	private static void check(Bundle b, String key, List<String> ids, String what, List<String> warnings) {
		Set<String> known = new HashSet<>();
		J.maps(b.ledger(), key).forEach((x) -> known.add(J.str(x, "id")));
		ids.stream()
			.filter((id) -> !known.contains(id))
			.forEach((id) -> warnings.add(what + " " + id + " is not in the ledger of " + b.projectKey() + " " + b.version()));
	}

}
