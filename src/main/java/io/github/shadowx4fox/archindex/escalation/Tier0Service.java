package io.github.shadowx4fox.archindex.escalation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.shadowx4fox.archindex.ArchIndexProperties.Escalation;
import io.github.shadowx4fox.archindex.ArchIndexProperties.Target;
import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * A RUN team's Tier 0 request, on the pattern of {@link EscalationService}. The IDE agent works in the service
 * repository, and the four Tier 0 writes belong in the architecture repository, so it <em>requests</em> them:
 * {@link #preview} checks the request against the latest release as {@code tier0.js prepare} would — {@code validate},
 * then the components, the ADRs and the TDR's move on the release's ledger — and returns the issue exactly as
 * {@code tier0.js render} writes it ({@code [T0-?]}), with the commands that file it. The architecture repository
 * writes the record ({@code architecture-tier0.yml}) after checking it again. {@link #record} logs where it went. The
 * server writes nothing, calls no host and holds no token; a project files on the host of its escalations
 * ({@code archindex.escalation}).
 */
public class Tier0Service {

	private static final Logger audit = LoggerFactory.getLogger("archindex.tier0");

	private final Bundles bundles;

	private final Escalation settings;

	public Tier0Service(Bundles bundles, Escalation settings) {
		this.bundles = bundles;
		this.settings = settings;
	}

	public Map<String, Object> preview(String project, Tier0.Request request) {
		Bundle b = bundles.bundle(project, null);
		Map<String, Object> support = EscalationService.policyOf(b);
		Tier0.Policy policy = Tier0.Policy.of(support);
		if (policy.kinds().isEmpty()) {
			throw new ToolException(b.projectKey() + " " + b.version() + " lists no Tier 0 kinds (support.tier0_kinds): it was "
					+ "exported before the plugin's v10.7.0 — re-export and publish it");
		}
		Target target = target(b.projectKey());
		List<String> warnings = new ArrayList<>();
		List<String> errors = new ArrayList<>(Tier0.validate(request, policy));
		errors.addAll(Tier0.oneLine(request));
		List<String> names = new ArrayList<>();
		for (String n : Tier0.list(request.components())) {
			try {
				String name = J.str(bundles.component(b, n), "name");
				if (!name.equals(n)) {
					warnings.add("component \"" + n + "\" is requested under its canonical name \"" + name + "\"");
				}
				names.add(name);
			}
			catch (ToolException e) {
				errors.add("component \"" + n + "\" is not a canonical name of this architecture — a name in doubt is no Tier 0 write");
			}
		}
		Tier0.Request req = request.withComponents(names);
		if (errors.isEmpty()) {
			Map<String, String> tdrs = new LinkedHashMap<>();
			Set<String> adrs = new HashSet<>();
			J.maps(b.ledger(), "tdrs").forEach((x) -> tdrs.put(J.str(x, "id"), J.str(x, "status")));
			J.maps(b.ledger(), "adrs").forEach((x) -> adrs.add(J.str(x, "id")));
			errors.addAll(Tier0.against(req, policy, new Tier0.Ledger(tdrs, adrs)));
		}
		if (!errors.isEmpty()) {
			throw new ToolException("the Tier 0 request cannot be filed as it is:\n- " + String.join("\n- ", errors));
		}
		String architecture = J.str(support, "released") != null ? J.str(support, "released")
				: J.str(J.map(b.manifest(), "git"), "tag");
		Escalate.Issue issue = Tier0.issue(req, policy, architecture);
		if (issue.body().length() > EscalationService.MAX_BODY) {
			throw new ToolException("the issue body is " + issue.body().length() + " characters, over " + EscalationService.MAX_BODY
					+ ": shorten the request, the evidence notes or the fields");
		}
		// no fingerprint in the body, which is tier0.js's byte for byte: the preview_id joins the two log lines
		String fingerprint = J.sha256(b.projectKey() + "\n" + issue.title() + "\n" + issue.body()).substring(0, 16);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("project", b.projectKey());
		out.put("version", b.version());
		out.put("preview_id", fingerprint);
		out.put("issue", Map.of("title", issue.title(), "labels", issue.labels(), "body", issue.body()));
		out.put("warnings", warnings);
		out.put("file_with", FilingPlan.of(target, issue, issue.body(), fingerprint, FilingPlan.Flavor.TIER0));
		out.put("next", "Nothing has been written, and nothing is written in this repository: the architecture repository "
				+ "writes the record once the issue is filed. Show the user this issue as it stands — title, labels and body — "
				+ "with the warnings, and ask whether to file it. Only after the user says yes, follow file_with.steps.");
		audit.info("tier0 preview project={} version={} kind={} requested_by=\"{}\" preview_id={}", b.projectKey(), b.version(),
				req.kind(), req.requestedBy() == null ? "" : req.requestedBy(), fingerprint);
		return out;
	}

	/** The issue the agent filed for a preview: logged, so the requests per team are counted. */
	public Map<String, Object> record(String project, String previewId, String url) {
		Target target = target(project);
		String id = previewId == null ? "" : previewId.strip();
		if (!EscalationService.PREVIEW_ID.matcher(id).matches()) {
			throw new ToolException("preview_id is the one preview_tier0 returned (16 hex characters), not '" + previewId + "'");
		}
		String u = url == null ? "" : url.strip();
		if (!EscalationService.onTarget(target, u)) {
			throw new ToolException("'" + url + "' is not an issue of " + target.repository() + " on " + target.server()
					+ ", where " + project + " files its Tier 0 requests");
		}
		audit.info("tier0 filed project={} preview_id={} url={}", project, id, u);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", Tier0.UNNUMBERED);
		out.put("project", project);
		out.put("preview_id", id);
		out.put("url", u);
		out.put("next", "Filed. The architecture repository writes the record once the issue carries the label "
				+ Tier0.LABEL + " (architecture-tier0.yml), or refuses it there with the reason; nothing changes in this repository.");
		return out;
	}

	private Target target(String project) {
		return settings.target(project)
			.orElseThrow(() -> new ToolException("this server files no Tier 0 requests for " + project
					+ ": its git host is not configured (archindex.escalation). Tell the user to make the change in the "
					+ "architecture repository, or raise it with the SA office hours."));
	}

}
