package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.ArchIndexProperties.Escalation;
import io.github.shadowx4fox.archindex.ArchIndexProperties.GitHost;
import io.github.shadowx4fox.archindex.ArchIndexProperties.Target;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.escalation.Escalate;
import io.github.shadowx4fox.archindex.escalation.EscalationService;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * A RUN team's escalation: checked against the latest release's support policy, rendered as the plugin renders it,
 * filed by the agent with the commands the server names for the project's host — the server writes nothing.
 */
class EscalationTest {

	@TempDir
	Path tmp;

	static final Target GITHUB = new Target("order-desk", GitHost.GITHUB, "acme/order-desk-architecture", null, null);

	int stores;

	/** A store of its own for each call. */
	BundleStore store() {
		return new BundleStore(tmp.resolve("data-" + ++stores), 50_000_000);
	}

	Bundles bundles(String... names) {
		BundleStore store = store();
		for (String n : names) {
			Path b = Fixtures.bundle(n);
			boolean draft = n.equals("draft");
			store.publish("order-desk", draft ? "1.4.0" : n.replace("-revised", ""), draft ? "draft" : "released",
					new ByteArrayInputStream(Fixtures.tgz(b)));
		}
		return new Bundles(store);
	}

	EscalationService service(Bundles bundles, Target... targets) {
		return new EscalationService(bundles, new Escalation(List.of(targets)));
	}

	static Escalate.Request request(String route, String... components) {
		return new Escalate.Request("Hub API calls the partner ledger with a shared client secret",
				"Can we call the partner ledger from Hub API with the client secret we were given?", List.of("security-iam"),
				List.of(new Escalate.Evidence("security-iam", "docs/07-security-architecture.md:88",
						"mTLS between services; the change adds a shared secret")),
				List.of("Store the secret in the vault and send it as a header."),
				List.of("Is a shared secret acceptable for the partner ledger?"), List.of(components), List.of(), List.of(),
				List.of(), "RUN team", route);
	}

	@SuppressWarnings("unchecked")
	static List<String> argv(Map<String, Object> plan, String step) {
		return (List<String>) J.map(plan, step).get("argv");
	}

	@Test
	void thePreviewIsThePluginsIssueWithTheCommandsThatFileIt() throws Exception {
		EscalationService s = service(bundles("1.3.0", "1.4.0"), GITHUB);
		Map<String, Object> p = s.preview("order-desk", request(null, "hub-api"));
		assertThat(p.get("version")).isEqualTo("1.4.0");
		Map<String, Object> issue = J.map(p, "issue");
		Map<String, Object> golden = J.map(EscalateTest.golden().get(0), "issue");
		String id = J.str(p, "preview_id");
		assertThat(id).matches("[0-9a-f]{16}");
		// the plugin's body, then the fingerprint on its own line, which escalate.js import reads past
		assertThat(J.str(issue, "body")).isEqualTo(J.str(golden, "body") + "<!-- sa-escalation: " + id + " -->\n");
		assertThat(J.str(issue, "title")).isEqualTo(J.str(golden, "title")).startsWith("[ESC-?] ");
		assertThat(J.strings(issue, "labels")).containsExactly("architecture-escalation", "tier-1", "trigger:security-iam");
		// the components by canonical name; ids the ledger lacks are flagged, not refused
		assertThat(J.strings(p, "warnings")).containsExactly("component \"hub-api\" is filed under its canonical name \"Hub API\"");
		Escalate.Request r = request(null, "Hub API", "Partner Ledger");
		Escalate.Request ids = new Escalate.Request(r.title(), r.request(), r.triggers(), r.evidence(), r.proposal(), r.questions(),
				r.components(), List.of("I-02", "I-99"), List.of("ADR-012", "ADR-404"), List.of("TDR-001"), r.requestedBy(), r.route());
		assertThat(J.strings(s.preview("order-desk", ids), "warnings")).containsExactly(
				"component \"Partner Ledger\" is not a canonical name in the ledger of order-desk 1.4.0",
				"integration I-99 is not in the ledger of order-desk 1.4.0", "ADR ADR-404 is not in the ledger of order-desk 1.4.0");
		assertThat(J.maps(p, "open_in_release")).extracting((e) -> e.get("id")).containsExactly("ESC-001");
		// the same request previews the same id: a second filing is found by it
		assertThat(s.preview("order-desk", request(null, "Hub API")).get("preview_id")).isEqualTo(id);

		Map<String, Object> plan = J.map(p, "file_with");
		assertThat(plan.get("repository")).isEqualTo("acme/order-desk-architecture");
		assertThat(argv(plan, "check_first")).containsExactly("gh", "issue", "list", "-R", "acme/order-desk-architecture",
				"--state", "open", "--limit", "200", "--json", "number,title,url,body");
		assertThat(argv(plan, "create")).containsExactly("gh", "issue", "create", "-R", "acme/order-desk-architecture",
				"--title", J.str(issue, "title"), "--body-file", "<body-file>", "--label", "architecture-escalation", "--label",
				"tier-1", "--label", "trigger:security-iam");
		assertThat(J.strings(plan, "steps")).hasSize(4).first().asString().contains(id);
		assertThat(J.strings(plan, "steps").get(3)).contains("record_escalation with project, preview_id", "and url (");
	}

	@Test
	void anUnattendedBuildFilesTheSameIssueWithItsOwnIdentity() {
		EscalationService s = service(bundles("1.4.0"), GITHUB);
		Map<String, Object> attended = s.preview("order-desk", request(null, "Hub API"));
		Map<String, Object> u = s.preview("order-desk", request(null, "Hub API"), "unattended");
		// the issue, its labels and its commands are the same: an escalation writes nothing, so it needs no approval
		assertThat(u.get("issue")).isEqualTo(attended.get("issue"));
		assertThat(u.get("preview_id")).isEqualTo(attended.get("preview_id"));
		assertThat(argv(J.map(u, "file_with"), "create")).isEqualTo(argv(J.map(attended, "file_with"), "create"));
		// who files and where it is told: the build's own identity, its pull request — no user to ask
		assertThat(J.strings(J.map(u, "file_with"), "steps")).anySatisfy((x) -> assertThat(x).contains("this build's own identity"))
			.allSatisfy((x) -> assertThat(x).doesNotContain("the user").doesNotContain("the developer"))
			.last().asString().contains("list the issue in your pull request");
		assertThat(J.str(u, "next")).contains("Nobody attends this build").doesNotContain("Show the user");
		assertThat(J.strings(J.map(s.preview("order-desk", request(null, "Hub API"), " attended "), "file_with"), "steps"))
			.isEqualTo(J.strings(J.map(attended, "file_with"), "steps"));
		Map<String, Object> gitea = J.map(service(bundles("1.4.0"), new Target("order-desk", GitHost.GITEA, "acme/arch", "git.acme.test", null))
			.preview("order-desk", request(null, "Hub API"), "unattended"), "file_with");
		assertThat(J.map(J.map(J.map(gitea, "create"), "request"), "headers").get("Authorization").toString()).contains("this build's own identity");
		assertThatThrownBy(() -> s.preview("order-desk", request(null, "Hub API"), "nobody")).isInstanceOf(ToolException.class)
			.hasMessageContaining("supervision is \"attended\" (the default) or \"unattended\"");
	}

	@Test
	void eachHostGetsItsOwnCommands() {
		Bundles b = bundles("1.4.0");
		Map<String, Object> gl = J.map(service(b, new Target("order-desk", GitHost.GITLAB, "acme/arch/order-desk", "gitlab.acme.test", null))
			.preview("order-desk", request("tier-2", "Hub API")), "file_with");
		assertThat(argv(gl, "create")).containsExactly("glab", "issue", "create", "-R", "https://gitlab.acme.test/acme/arch/order-desk",
				"--title", "[ESC-?] Hub API calls the partner ledger with a shared client secret", "--description-file", "<body-file>",
				"--label", "architecture-escalation,tier-2,trigger:security-iam", "--yes");

		Map<String, Object> ghe = J.map(service(b, new Target("order-desk", GitHost.GITHUB, "acme/arch", "https://github.acme.test/", null))
			.preview("order-desk", request(null, "Hub API")), "file_with");
		assertThat(argv(ghe, "create")).contains("github.acme.test/acme/arch");

		Map<String, Object> az = J.map(service(b, new Target("order-desk", GitHost.AZURE, "acme/Order Desk", null, "Task"))
			.preview("order-desk", request(null, "Hub API")), "file_with");
		List<String> create = argv(az, "create");
		assertThat(create).containsSequence("--org", "https://dev.azure.com/acme", "--project", "Order Desk", "--type", "Task");
		// Azure DevOps takes the description as HTML, in an argument
		assertThat(create.get(create.indexOf("--description") + 1)).startsWith("<pre>| | |").contains("&lt;!-- sa-escalation: ").endsWith("</pre>");
		assertThat(create).contains("System.Tags=architecture-escalation; tier-1; trigger:security-iam");

		Map<String, Object> gitea = J.map(service(b, new Target("order-desk", GitHost.GITEA, "acme/arch", "git.acme.test", null))
			.preview("order-desk", request(null, "Hub API")), "file_with");
		assertThat(J.str(J.map(J.map(gitea, "create"), "request"), "url")).isEqualTo("https://git.acme.test/api/v1/repos/acme/arch/issues");
		assertThatThrownBy(() -> service(b, new Target("order-desk", GitHost.GITEA, "acme/arch", null, null)).preview("order-desk", request(null)))
			.isInstanceOf(ToolException.class).hasMessageContaining("for Gitea, server");
	}

	@Test
	void aRequestTheRulesRefuseIsNotPreviewed() {
		EscalationService s = service(bundles("1.4.0"), GITHUB);
		Escalate.Request noEvidence = new Escalate.Request("T", "r", List.of("new-integration"), null, null, null, null, null, null,
				null, null, null);
		assertThatThrownBy(() -> s.preview("order-desk", noEvidence)).isInstanceOf(ToolException.class)
			.hasMessageContaining("trigger \"new-integration\" has no evidence");
	}

	@Test
	void onlyAReleasedRunTeamProjectWithAHostEscalates() throws Exception {
		// the draft is never escalated against: the latest release is
		assertThatThrownBy(() -> service(bundles("draft"), GITHUB).preview("order-desk", request(null)))
			.isInstanceOf(ToolException.class).hasMessageContaining("holds no version 'latest'");
		assertThat(service(bundles("1.4.0", "draft"), GITHUB).preview("order-desk", request(null)).get("version")).isEqualTo("1.4.0");
		// no host configured for the project
		assertThatThrownBy(() -> service(bundles("1.4.0")).preview("order-desk", request(null)))
			.isInstanceOf(ToolException.class).hasMessageContaining("files no escalations for order-desk");
		// a project that is no RUN team's, and one before its first release
		assertThatThrownBy(() -> service(supportAs(Map.of("mode", "none")), GITHUB).preview("order-desk", request(null)))
			.isInstanceOf(ToolException.class).hasMessageContaining("not declared a RUN-team project");
		assertThatThrownBy(() -> service(supportAs(Map.of("mode", "run", "tier0", false)), GITHUB).preview("order-desk", request(null)))
			.isInstanceOf(ToolException.class).hasMessageContaining("no architecture release yet");
		assertThatThrownBy(() -> service(supportAs(null), GITHUB).preview("order-desk", request(null)))
			.isInstanceOf(ToolException.class).hasMessageContaining("before the plugin's v10.3.0");
	}

	/** 1.4.0 with another support block (none at all for {@code null}). */
	Bundles supportAs(Map<String, Object> support) throws Exception {
		Path b = Fixtures.copy(Fixtures.bundle("1.4.0"), tmp.resolve("b-" + ++stores));
		Map<String, Object> m = J.obj(Files.readAllBytes(b.resolve("manifest.json")));
		if (support == null) {
			m.remove("support");
		}
		else {
			m.put("support", support);
		}
		Files.writeString(b.resolve("manifest.json"), J.write(m));
		BundleStore store = store();
		store.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(b)));
		return new Bundles(store);
	}

	@Test
	void theFiledIssueIsRecordedOnlyOnTheProjectsRepository() {
		EscalationService s = service(bundles("1.4.0"), GITHUB,
				new Target("other", GitHost.AZURE, "acme/Order Desk", null, null));
		Map<String, Object> r = s.record("order-desk", "0123456789abcdef", "https://github.com/acme/order-desk-architecture/issues/41");
		assertThat(r).containsEntry("id", "ESC-?").containsEntry("url", "https://github.com/acme/order-desk-architecture/issues/41");
		assertThatThrownBy(() -> s.record("order-desk", "0123456789abcdef", "https://github.com/acme/elsewhere/issues/1"))
			.isInstanceOf(ToolException.class).hasMessageContaining("not an issue of acme/order-desk-architecture");
		assertThatThrownBy(() -> s.record("order-desk", "ESC-001", "https://github.com/acme/order-desk-architecture/issues/41"))
			.isInstanceOf(ToolException.class).hasMessageContaining("16 hex characters");
		assertThat(s.record("other", "0123456789abcdef", "https://dev.azure.com/acme/Order%20Desk/_workitems/edit/7")).containsKey("url");
		assertThat(s.record("other", "0123456789abcdef", "https://acme.visualstudio.com/Order%20Desk/_workitems/edit/7")).containsKey("url");
		assertThat(s.filing("order-desk")).containsEntry("enabled", true).containsEntry("host", "github");
		assertThat(s.filing("nobody")).containsEntry("enabled", false);
	}

}
