package io.github.shadowx4fox.archindex.escalation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.shadowx4fox.archindex.ArchIndexProperties.Target;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * How the agent files an escalation issue with the developer's own access — the plugin's {@code issuePlan} for the
 * project's target: first the open escalation issues to check (this preview filed already, or another covering the
 * same request), then the command that opens it. The body goes through a file, byte for byte, where the tool takes one
 * ({@code <body-file>}); Azure DevOps takes the description only as an argument, as HTML.
 */
final class FilingPlan {

	static final String BODY_FILE = "<body-file>";

	private static final String OPEN_STATES = "('Closed', 'Done', 'Removed', 'Resolved')";

	private FilingPlan() {
	}

	/** What the plan says about the issue it files: an escalation's, or a Tier 0 request's. */
	record Flavor(String noun, String titlePrefix, String recordTool) {

		static final Flavor ESCALATION = new Flavor("escalation", "[ESC-", "record_escalation");

		static final Flavor TIER0 = new Flavor("Tier 0 request", "[T0-", "record_tier0");

	}

	static Map<String, Object> of(Target t, Escalate.Issue issue, String body, String fingerprint) {
		return of(t, issue, body, fingerprint, Flavor.ESCALATION);
	}

	static Map<String, Object> of(Target t, Escalate.Issue issue, String body, String fingerprint, Flavor f) {
		if (t.host() == null || t.repository() == null || t.repository().isBlank() || t.server() == null) {
			throw new ToolException("the " + f.noun() + " target of " + t.project()
					+ " is incomplete on this server: it needs host, repository and, for Gitea, server");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("host", t.hostName());
		m.put("repository", t.repository());
		m.put("server", t.server());
		String web = "https://" + t.server() + "/" + t.repository();
		switch (t.host()) {
			case GITHUB -> {
				String repo = "github.com".equals(t.server()) ? t.repository() : t.server() + "/" + t.repository();
				m.put("check_first", run(List.of("gh", "issue", "list", "-R", repo, "--state", "open", "--limit", "200",
						"--json", "number,title,url,body")));
				List<String> create = new ArrayList<>(List.of("gh", "issue", "create", "-R", repo, "--title", issue.title(),
						"--body-file", BODY_FILE));
				issue.labels().forEach((l) -> create.addAll(List.of("--label", l)));
				m.put("create", run(create));
				m.put("labels", "gh refuses a label the repository does not have: when it does, run the same command without the --label pairs and tell the user which labels were skipped.");
				m.put("issue_url", "the URL gh prints");
			}
			case GITLAB -> {
				m.put("check_first", run(List.of("glab", "issue", "list", "-R", web, "--per-page", "100", "--output", "json")));
				m.put("create", run(List.of("glab", "issue", "create", "-R", web, "--title", issue.title(), "--description-file",
						BODY_FILE, "--label", String.join(",", issue.labels()), "--yes")));
				m.put("issue_url", "the URL glab prints");
			}
			case GITEA -> {
				String api = "https://" + t.server() + "/api/v1/repos/" + t.repository();
				Map<String, Object> auth = Map.of("Authorization", "token <a Gitea token of the developer's, with issue write access>");
				m.put("check_first", request("GET", api + "/issues?state=open&type=issues&limit=50", auth, null));
				m.put("labels_first", request("GET", api + "/labels?limit=50", auth, null));
				Map<String, Object> post = new LinkedHashMap<>();
				post.put("title", issue.title());
				post.put("body", "<the body, byte for byte>");
				post.put("labels", "<the ids of the labels named under issue.labels that labels_first lists; a missing one is skipped>");
				m.put("create", request("POST", api + "/issues", auth, post));
				m.put("issue_url", "html_url in the answer");
			}
			case AZURE -> {
				int i = t.repository().indexOf('/');
				if (i <= 0 || i == t.repository().length() - 1) {
					throw new ToolException("the Azure DevOps repository of " + t.project() + " must be organization/project, not "
							+ t.repository());
				}
				String org = "https://" + t.server() + "/" + t.repository().substring(0, i);
				String project = t.repository().substring(i + 1);
				m.put("check_first", run(List.of("az", "boards", "query", "--org", org, "--project", project, "--wiql",
						"SELECT [System.Id], [System.Title], [System.Description] FROM WorkItems WHERE [System.TeamProject] = @project"
								+ " AND [System.Title] CONTAINS '" + f.titlePrefix() + "' AND [System.State] NOT IN " + OPEN_STATES,
						"--output", "json")));
				m.put("create", run(List.of("az", "boards", "work-item", "create", "--org", org, "--project", project, "--type",
						t.workItemType(), "--title", issue.title(), "--description", Escalate.html(body), "--fields",
						"System.Tags=" + String.join("; ", issue.labels()), "--output", "json")));
				m.put("issue_url", "_links.html.href in the answer, else " + org + "/" + project + "/_workitems/edit/<id>");
			}
		}
		m.put("steps", List.of(
				"Run check_first. An open issue whose body holds " + fingerprint + " is this " + f.noun() + ", filed already: give the user its URL and file nothing. An open " + f.titlePrefix() + "…] issue that covers the same request is a duplicate: tell the user and file nothing.",
				"Write issue.body to a file in the system temp directory, byte for byte, and put its path where create says " + BODY_FILE + ".",
				"Run create, with each argument passed as it is (quote it for the shell, never retype it). If the developer cannot write issues on " + t.repository() + ", or the tool is not installed or logged in, say so and give them the title and the body to file by hand.",
				"Call " + f.recordTool() + " with the project, this preview_id and the issue's URL."));
		return m;
	}

	private static Map<String, Object> run(List<String> argv) {
		return Map.of("argv", argv);
	}

	private static Map<String, Object> request(String method, String url, Map<String, Object> headers, Object body) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("method", method);
		r.put("url", url);
		r.put("headers", headers);
		if (body != null) {
			r.put("json", body);
		}
		return Map.of("request", r);
	}

}
