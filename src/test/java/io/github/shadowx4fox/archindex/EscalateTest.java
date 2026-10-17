package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.escalation.Escalate;

/**
 * The escalation issue is the plugin's: {@code src/test/resources/escalation/issues.json} holds what {@code escalate.js}
 * renders for each request (scripts/make-fixtures.ts), and the port renders the same title, labels and body, byte for
 * byte. When the plugin's rendering changes, port the change and regenerate.
 */
class EscalateTest {

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> golden() throws Exception {
		Path f = Path.of(EscalateTest.class.getResource("/escalation/issues.json").toURI());
		return (List<Map<String, Object>>) J.MAPPER.readValue(Files.readString(f), List.class);
	}

	static Escalate.Policy policy() throws Exception {
		Map<String, Object> s = J.map(J.obj(Files.readAllBytes(Fixtures.bundle("1.4.0").resolve("manifest.json"))), "support");
		Map<String, String> triggers = new LinkedHashMap<>();
		Map<String, String> routes = new LinkedHashMap<>();
		J.map(s, "triggers").forEach((k, v) -> triggers.put(k, (String) v));
		J.map(s, "routes").forEach((k, v) -> routes.put(k, (String) v));
		return new Escalate.Policy(triggers, routes);
	}

	static Escalate.Request request(Map<String, Object> r) {
		return new Escalate.Request(J.str(r, "title"), J.str(r, "request"), J.strings(r, "triggers"),
				J.maps(r, "evidence").stream().map((e) -> new Escalate.Evidence(J.str(e, "trigger"), J.str(e, "where"), J.str(e, "note"))).toList(),
				J.strings(r, "proposal"), J.strings(r, "questions"), J.strings(r, "components"), J.strings(r, "integrations"),
				J.strings(r, "adrs"), J.strings(r, "tdrs"), J.str(r, "requested_by"), J.str(r, "route"));
	}

	@Test
	void theIssueIsThePluginsByteForByte() throws Exception {
		Escalate.Policy p = policy();
		List<Map<String, Object>> cases = golden();
		assertThat(cases).extracting((c) -> c.get("name")).contains("order-desk", "every-field", "low-confidence", "text-that-looks-like-markup");
		for (Map<String, Object> c : cases) {
			Escalate.Request req = request(J.map(c, "request"));
			assertThat(Escalate.validate(req, p)).as(J.str(c, "name")).isEmpty();
			Escalate.Issue issue = Escalate.issue(req, p, Escalate.UNNUMBERED, J.str(c, "architecture"), "—");
			Map<String, Object> want = J.map(c, "issue");
			assertThat(issue.title()).as(J.str(c, "name")).isEqualTo(J.str(want, "title"));
			assertThat(issue.labels()).as(J.str(c, "name")).isEqualTo(J.strings(want, "labels"));
			assertThat(issue.body()).as(J.str(c, "name")).isEqualTo(J.str(want, "body"));
		}
	}

	@Test
	void textIsTrimmedTheWayJavaScriptTrimsIt() {
		// ECMAScript white space: U+FEFF and U+00A0 go, U+0085 stays — the reverse of what String.strip() would do with them
		assertThat(Escalate.trim("﻿  a 　 ")).isEqualTo("a");
		assertThat(Escalate.trim("a\u0085")).isEqualTo("a\u0085");
		assertThat("﻿a".strip()).isNotEqualTo("a");
	}

	@Test
	void theRulesOfValidateAreThePlugins() throws Exception {
		Escalate.Policy p = policy();
		Escalate.Request bad = new Escalate.Request("Two\nlines", " ", List.of("security-iam", "made-up", "low-confidence"),
				List.of(new Escalate.Evidence("regulatory", "docs/x.md:1", null)), null, null, null, null, null, null, null, "tier-3");
		assertThat(Escalate.validate(bad, p)).containsExactly("title must be one line",
				"request (the RUN team's words, verbatim) is required",
				"unknown trigger \"made-up\" — one of new-integration, data-classification, cross-domain, security-iam, regulatory, new-technology, low-confidence",
				"evidence for \"regulatory\", which is not among the triggers",
				"trigger \"security-iam\" has no evidence — a file and line, or the request's own words",
				"trigger \"made-up\" has no evidence — a file and line, or the request's own words",
				"route must be tier-1 or tier-2");
		Escalate.Request none = new Escalate.Request("T", "r", List.of(), null, null, null, null, null, null, null, null, null);
		assertThat(Escalate.validate(none, p)).containsExactly("at least one trigger is required");
	}

	@Test
	void aNumberedRecordClosesInsteadOfImporting() throws Exception {
		Escalate.Request req = request(J.map(golden().get(0), "request"));
		Escalate.Issue issue = Escalate.issue(req, policy(), "ESC-004", "architecture-v1.4.0", "escalations/ESC-004-x.md");
		assertThat(issue.title()).startsWith("[ESC-004] ");
		assertThat(issue.body()).contains("| Record | escalations/ESC-004-x.md |")
			.endsWith("_Close it with `escalate.js close <project> ESC-004 --by <ADR-NNN|TDR-NNN|architecture-vX.Y.Z>`._\n");
	}

}
