package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.ArchIndexProperties.Escalation;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.escalation.Escalate;
import io.github.shadowx4fox.archindex.escalation.Tier0;
import io.github.shadowx4fox.archindex.escalation.Tier0Service;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * The Tier 0 request issue is the plugin's: {@code src/test/resources/tier0/issues.json} holds what {@code tier0.js}
 * renders for each request and what its {@code validate()} refuses (scripts/make-fixtures.ts), and the port renders and
 * refuses the same, byte for byte. When the plugin's format changes, port the change and regenerate.
 */
class Tier0Test {

	@TempDir
	Path tmp;

	private int stores;

	static Map<String, Object> golden() throws Exception {
		return J.obj(Files.readAllBytes(Path.of(Tier0Test.class.getResource("/tier0/issues.json").toURI())));
	}

	static Map<String, Object> support() throws Exception {
		return J.map(J.obj(Files.readAllBytes(Fixtures.bundle("1.4.0").resolve("manifest.json"))), "support");
	}

	static Tier0.Request request(Map<String, Object> r) {
		List<Tier0.Evidence> evidence = r.get("evidence") == null ? null
				: J.list(r, "evidence").stream().map((e) -> e == null ? null : new Tier0.Evidence(J.str(e, "where"), J.str(e, "note"))).toList();
		return new Tier0.Request(J.str(r, "kind"), J.str(r, "title"), J.str(r, "request"),
				r.get("components") == null ? null : J.strings(r, "components"), J.str(r, "requested_by"), evidence, J.map(r, "fields"),
				J.str(r, "supervision"));
	}

	@Test
	void theIssueIsThePluginsByteForByte() throws Exception {
		Tier0.Policy p = Tier0.Policy.of(support());
		Map<String, Object> g = golden();
		List<Map<String, Object>> cases = J.maps(g, "cases");
		assertThat(cases).extracting((c) -> c.get("name"))
			.contains("tdr-workaround", "tdr-status", "adr-proposed", "doc-fix", "every-edge", "unattended", "attended",
					"unattended-auto", "unattended-not-auto", "attended-auto");
		for (Map<String, Object> c : cases) {
			Tier0.Request req = request(J.map(c, "request"));
			assertThat(Tier0.validate(req, p)).as(J.str(c, "name")).isEmpty();
			assertThat(Tier0.oneLine(req)).as(J.str(c, "name")).isEmpty();
			// the case's tier0.auto, as support.tier0_auto carries it
			Map<String, Object> support = new LinkedHashMap<>(support());
			support.put("tier0_auto", c.get("auto"));
			Escalate.Issue issue = Tier0.issue(req, Tier0.Policy.of(support), J.str(g, "architecture"));
			Map<String, Object> want = J.map(c, "issue");
			assertThat(issue.title()).as(J.str(c, "name")).isEqualTo(J.str(want, "title"));
			assertThat(issue.labels()).as(J.str(c, "name")).isEqualTo(J.strings(want, "labels"));
			assertThat(issue.body()).as(J.str(c, "name")).isEqualTo(J.str(want, "body"));
		}
	}

	@Test
	void validateRefusesWhatThePluginRefusesWithItsWords() throws Exception {
		Tier0.Policy p = Tier0.Policy.of(support());
		List<Map<String, Object>> refused = J.maps(golden(), "refused");
		assertThat(refused).hasSizeGreaterThanOrEqualTo(10);
		for (Map<String, Object> c : refused) {
			assertThat(Tier0.validate(request(J.map(c, "request")), p)).as(J.str(c, "name")).isEqualTo(J.strings(c, "errors"));
		}
	}

	@Test
	void theFieldsAreJavaScriptValues() {
		// JSON.stringify(fields, null, 2): JavaScript's numbers and key order; the expected texts are bun's
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("b", 1);
		f.put("10", 2);
		f.put("2", 3);
		f.put("01", 4);
		f.put("4294967295", 5);
		f.put("4294967294", 6);
		f.put("a", Map.of());
		assertThat(Tier0.stringify(f)).isEqualTo("""
				{
				  "2": 3,
				  "10": 2,
				  "4294967294": 6,
				  "b": 1,
				  "01": 4,
				  "4294967295": 5,
				  "a": {}
				}""");
		Map<Double, String> js = new LinkedHashMap<>();
		js.put(42.0, "42");
		js.put(-0.0, "0");
		js.put(0.1, "0.1");
		js.put(0.000001, "0.000001");
		js.put(1e-7, "1e-7");
		js.put(123456789012345680000.0, "123456789012345680000");
		js.put(1e21, "1e+21");
		js.put(1.5e300, "1.5e+300");
		js.put(-2.5e-9, "-2.5e-9");
		js.put(1.0 / 3, "0.3333333333333333");
		js.put(9007199254740993.0, "9007199254740992");
		js.put(Double.MIN_VALUE, "5e-324");
		js.put(100.0, "100");
		js.forEach((d, s) -> assertThat(Tier0.stringify(d)).as(String.valueOf(d)).isEqualTo(s));
		assertThat(Tier0.stringify("\ud800x\udc00 \u007f\u0000")).isEqualTo("\"\\ud800x\\udc00 \u007f\\u0000\"");
		// Number(): what fields.line may be
		assertThat(List.of(" 0x10 ", "1e3", ".5", "5.", "0b11", "+0x1", "1_0", " 12﻿", "Infinity", "1d"))
			.extracting(Tier0::number)
			.containsExactly(16.0, 1000.0, 0.5, 5.0, 3.0, Double.NaN, Double.NaN, 12.0, Double.POSITIVE_INFINITY, Double.NaN);
	}

	@Test
	void aTitleOrEvidenceOnTwoLinesIsRefusedByTheServer() {
		Tier0.Request r = new Tier0.Request("doc-fix", "a\r\nb", "r", null, null,
				List.of(new Tier0.Evidence("svc@1 a.go:1", "two\nlines")), Map.of());
		assertThat(Tier0.oneLine(r)).containsExactly("title must be one line", "evidence: where and note are one line each");
	}

	// ---- the service, on a release

	Tier0Service service(Consumer<Map<String, Object>> support) throws Exception {
		int n = ++stores;
		Path b = Fixtures.copy(Fixtures.bundle("1.4.0"), tmp.resolve("b-" + n));
		Map<String, Object> m = J.obj(Files.readAllBytes(b.resolve("manifest.json")));
		support.accept(J.map(m, "support"));
		Files.writeString(b.resolve("manifest.json"), J.write(m));
		BundleStore store = new BundleStore(tmp.resolve("data-" + n), 50_000_000);
		store.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(b)));
		return new Tier0Service(new Bundles(store), new Escalation(List.of(EscalationTest.GITHUB)));
	}

	static Tier0.Request move(String tdr, String to) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("tdr", tdr);
		f.put("to", to);
		f.put("note", "the job replaces the script");
		return new Tier0.Request("tdr-status", "Move " + tdr, "replace the manual ledger fix", List.of("ledger-store"),
				"Desk team", List.of(new Tier0.Evidence("ledger-jobs@9f00d1e src/fix/Job.java:1", null)), f);
	}

	@Test
	@SuppressWarnings("unchecked")
	void thePreviewChecksTheRequestOnTheReleasesLedger() throws Exception {
		Tier0Service s = service((x) -> {
		});
		Map<String, Object> ok = s.preview("order-desk", move("TDR-001", "In Progress"));
		Map<String, Object> issue = (Map<String, Object>) ok.get("issue");
		assertThat(J.str(issue, "title")).isEqualTo("[T0-?] Move TDR-001");
		assertThat(J.str(issue, "body")).contains("| Components | Ledger Store |").doesNotContain(ok.get("preview_id").toString());
		assertThat(J.strings(ok, "warnings")).containsExactly("component \"ledger-store\" is requested under its canonical name \"Ledger Store\"");
		assertThat(J.strings(J.map(ok, "file_with"), "steps").get(0)).contains("with this title and this body").doesNotContain("holds");
		assertThat(J.strings(J.map(ok, "file_with"), "steps").get(3)).contains("record_tier0 with project, preview_id", "and url (");
		assertThat(ok).doesNotContainKey("open_in_release");

		// Open in the release, maybe In Progress in main since: the architecture repository checks it
		Map<String, Object> second = s.preview("order-desk", move("TDR-001", "Resolved"));
		assertThat(J.str(second, "next")).startsWith("TDR-001 is Open in 1.4.0; this move assumes it is In Progress in main — the architecture repository checks it. ");
		assertThat(J.str((Map<String, Object>) second.get("issue"), "body")).doesNotContain("assumes").doesNotContain("1.4.0;");
		assertThat(J.str(ok, "next")).startsWith("Nothing has been written");
		assertThatThrownBy(() -> s.preview("order-desk", move("TDR-001", "Open"))).hasMessageContaining("already `Open`");
		assertThatThrownBy(() -> s.preview("order-desk", move("TDR-404", "In Progress"))).hasMessageContaining("TDR-404 has no record under tdr/");
		// created since the release, and no draft lists it
		assertThatThrownBy(() -> s.preview("order-desk", move("TDR-002", "In Progress"))).hasMessageContaining("TDR-002 has no record under tdr/");
		assertThatThrownBy(() -> s.preview("order-desk", move("TDR-001", "Won't Fix")))
			.hasMessageContaining("a move to Won't Fix is a decision, not a Tier 0 write — the architect makes it");

		Tier0.Request nowhere = move("TDR-001", "In Progress").withComponents(List.of("Nowhere"));
		assertThatThrownBy(() -> s.preview("order-desk", nowhere))
			.hasMessageContaining("component \"Nowhere\" is not a canonical name of this architecture — a name in doubt is no Tier 0 write")
			.hasMessageNotContaining("has no record");

		Map<String, Object> f = new LinkedHashMap<>();
		f.put("description", "d");
		f.put("impact", "i");
		f.put("solutions", List.of("s"));
		f.put("owner_technical", "o");
		f.put("adrs", List.of("ADR-101", "ADR-999"));
		Tier0.Request workaround = new Tier0.Request("tdr-workaround", "A workaround", "r", List.of("Hub API"), null,
				List.of(new Tier0.Evidence("svc@1 a.go:1", null)), f);
		assertThatThrownBy(() -> s.preview("order-desk", workaround)).hasMessageContaining("fields.adrs: ADR-999 does not exist")
			.hasMessageNotContaining("ADR-101");
		assertThatThrownBy(() -> s.preview("order-desk", new Tier0.Request("big-bang", "t", "r", null, null, null, null)))
			.isInstanceOf(ToolException.class)
			.hasMessageContaining("kind \"big-bang\" is not a Tier 0 write — one of tdr-workaround, tdr-status, adr-proposed, doc-fix");
	}

	@Test
	@SuppressWarnings("unchecked")
	void aRecordCreatedSinceTheReleaseIsCheckedOnTheDraft() throws Exception {
		// a Tier 0 merge published the draft: TDR-002 (Open) and ADR-102 (Proposed) are in it, not in 1.4.0
		int n = ++stores;
		BundleStore store = new BundleStore(tmp.resolve("data-" + n), 50_000_000);
		store.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("1.4.0"))));
		store.publish("order-desk", "1.4.0", "draft", new ByteArrayInputStream(Fixtures.tgz(Fixtures.bundle("draft"))));
		Tier0Service s = new Tier0Service(new Bundles(store), new Escalation(List.of(EscalationTest.GITHUB)));
		String draft = "the draft of " + J.str(J.obj(Files.readAllBytes(Fixtures.bundle("draft").resolve("manifest.json"))), "generated_at")
			.substring(0, 10);

		Map<String, Object> ok = s.preview("order-desk", move("TDR-002", "In Progress"));
		assertThat(ok.get("version")).isEqualTo("1.4.0");
		assertThat(J.str(ok, "next")).startsWith("TDR-002 is not in 1.4.0; it is Open in " + draft
				+ "; this move assumes it still is in main — the architecture repository checks it. Nothing has been written");
		assertThat(J.str((Map<String, Object>) ok.get("issue"), "body")).contains("TDR-002").doesNotContain("assumes");
		assertThat(J.str(s.preview("order-desk", move("TDR-002", "Resolved")), "next")).startsWith("TDR-002 is not in 1.4.0; it is Open in "
				+ draft + "; this move assumes it is In Progress in main — the architecture repository checks it. ");
		// a record in the release is checked on the release, as before
		assertThat(J.str(s.preview("order-desk", move("TDR-001", "In Progress")), "next")).startsWith("Nothing has been written");
		assertThatThrownBy(() -> s.preview("order-desk", move("TDR-002", "Open"))).hasMessageContaining("TDR-002: Open → Open is refused");
		assertThatThrownBy(() -> s.preview("order-desk", move("TDR-404", "In Progress"))).hasMessageContaining("TDR-404 has no record under tdr/");

		Map<String, Object> f = new LinkedHashMap<>();
		f.put("description", "d");
		f.put("impact", "i");
		f.put("solutions", List.of("s"));
		f.put("owner_technical", "o");
		f.put("adrs", List.of("ADR-101", "ADR-102"));
		Tier0.Request workaround = new Tier0.Request("tdr-workaround", "A workaround", "r", List.of("Hub API"), null,
				List.of(new Tier0.Evidence("svc@1 a.go:1", null)), f);
		assertThat(J.str(s.preview("order-desk", workaround), "next")).startsWith("ADR-102 is not in 1.4.0; it is in " + draft
				+ ", and this request assumes it is in main — the architecture repository checks it. ");
		f.put("adrs", List.of("ADR-102", "ADR-999"));
		assertThatThrownBy(() -> s.preview("order-desk", workaround)).hasMessageContaining("fields.adrs: ADR-999 does not exist")
			.hasMessageNotContaining("ADR-102");
	}

	@Test
	@SuppressWarnings("unchecked")
	void anUnattendedBuildFilesWithoutTheKey() throws Exception {
		Tier0Service s = service((x) -> {
		});
		Tier0.Request r = move("TDR-001", "In Progress");
		Tier0.Request u = new Tier0.Request(r.kind(), r.title(), r.request(), r.components(), r.requestedBy(), r.evidence(), r.fields(),
				" unattended ");
		Map<String, Object> attended = s.preview("order-desk", r);
		Map<String, Object> p = s.preview("order-desk", u);
		Map<String, Object> issue = (Map<String, Object>) p.get("issue");
		// tier0.js for supervision unattended: only tier0:<kind>, its own footer; the rest of the body as attended
		assertThat(J.strings(issue, "labels")).containsExactly("tier0:tdr-status");
		assertThat(J.str(issue, "body")).contains("_Tier 0, filed by an unattended build agent: a person reviews it and applies the label `architecture-tier0` — that is the approval;");
		String footer = "(?m)^_Tier 0[^\\n]*_$";
		assertThat(J.str(issue, "body").replaceAll(footer, "")).isEqualTo(J.str(J.map(attended, "issue"), "body").replaceAll(footer, ""));
		List<String> create = (List<String>) J.map(J.map(p, "file_with"), "create").get("argv");
		assertThat(create).containsSequence("--label", "tier0:tdr-status").doesNotContain("architecture-tier0");
		assertThat((List<String>) J.map(J.map(attended, "file_with"), "create").get("argv")).containsSequence("--label", "architecture-tier0");
		assertThat(J.strings(J.map(p, "file_with"), "steps")).anySatisfy((x) -> assertThat(x).contains("never add the label architecture-tier0"));
		assertThat(J.str(p, "next")).contains("never apply it yourself", "list the issue in your pull request").doesNotContain("Show the user");
		assertThatThrownBy(() -> s.preview("order-desk", new Tier0.Request(r.kind(), r.title(), r.request(), r.components(),
				r.requestedBy(), r.evidence(), r.fields(), "Unattended"))).hasMessageContaining("supervision is \"attended\"");
	}

	@Test
	@SuppressWarnings("unchecked")
	void theArchitectureKeysAKindItListsInTier0AutoAndTheAgentNever() throws Exception {
		Tier0Service s = service((x) -> x.put("tier0_auto", List.of("tdr-status")));
		Tier0.Request r = move("TDR-001", "In Progress");
		Tier0.Request u = new Tier0.Request(r.kind(), r.title(), r.request(), r.components(), r.requestedBy(), r.evidence(), r.fields(),
				"unattended");
		Map<String, Object> p = s.preview("order-desk", u);
		Map<String, Object> issue = (Map<String, Object>) p.get("issue");
		// tier0.js 10.13: only tier0:<kind> — the architecture repository keys it for a trusted agent — and its own footer
		assertThat(J.strings(issue, "labels")).containsExactly("tier0:tdr-status");
		assertThat(J.str(issue, "body")).contains("_Tier 0, filed by an unattended build agent: this architecture writes `tdr-status` from a trusted agent without a person's label (`tier0.auto`) — the architecture repository keys it itself");
		assertThat((List<String>) J.map(J.map(p, "file_with"), "create").get("argv")).doesNotContain("architecture-tier0");
		assertThat(J.strings(J.map(p, "file_with"), "steps")).anySatisfy((x) -> assertThat(x).contains("never add the label architecture-tier0", "keys this kind itself"))
			.noneSatisfy((x) -> assertThat(x).contains("that is the approval"));
		assertThat(J.str(p, "next")).contains("support.tier0_auto", "Never apply architecture-tier0", "owner/repo#N", "list the issue in your pull request");
		assertThat(J.strings(p, "warnings")).noneSatisfy((x) -> assertThat(x).contains("owner/repo@sha"));
		// a kind the architecture does not list: no key, as before
		Map<String, Object> w = new LinkedHashMap<>();
		w.put("description", "Hub API caches the stock lookup.");
		w.put("impact", "Stale stock for 5 minutes.");
		w.put("solutions", List.of("Push stock events"));
		w.put("owner_technical", "Desk team");
		Map<String, Object> other = s.preview("order-desk", new Tier0.Request("tdr-workaround", "Cache", "build within p95",
				List.of("hub-api"), "Desk team", r.evidence(), w, "unattended"));
		assertThat(J.strings(J.map(other, "issue"), "labels")).containsExactly("tier0:tdr-workaround");
		// a move to Resolved keyed by the agent names the commit that resolved it, owner/repo@sha
		Map<String, Object> resolved = s.preview("order-desk", new Tier0.Request(r.kind(), r.title(), r.request(), r.components(),
				r.requestedBy(), r.evidence(), Map.of("tdr", "TDR-001", "to", "Resolved", "note", "fixed"), "unattended"));
		assertThat(J.strings(resolved, "warnings")).anySatisfy((x) -> assertThat(x).contains("owner/repo#N", "owner/repo@sha", "refuses it"));
		// its pull request, open or merged, or a commit: no warning — the architecture repository waits for the merge
		for (String where : List.of("acme/ledger-jobs#42", "acme/ledger-jobs@9f00d1e src/fix/Job.java:1")) {
			Map<String, Object> named = s.preview("order-desk", new Tier0.Request(r.kind(), r.title(), r.request(), r.components(),
					r.requestedBy(), List.of(new Tier0.Evidence(where, null)), Map.of("tdr", "TDR-001", "to", "Resolved", "note", "fixed"),
					"unattended"));
			assertThat(J.strings(named, "warnings")).as(where).noneSatisfy((x) -> assertThat(x).contains("owner/repo"));
		}
		// JavaScript's \b is ASCII: a letter after the number is a word, a non-ASCII one is not
		for (String where : List.of("acme/ledger-jobs#42x", "ledger-jobs#42")) {
			Map<String, Object> bad = s.preview("order-desk", new Tier0.Request(r.kind(), r.title(), r.request(), r.components(),
					r.requestedBy(), List.of(new Tier0.Evidence(where, null)), Map.of("tdr", "TDR-001", "to", "Resolved", "note", "fixed"),
					"unattended"));
			assertThat(J.strings(bad, "warnings")).as(where).anySatisfy((x) -> assertThat(x).contains("owner/repo#N"));
		}
		Map<String, Object> accented = s.preview("order-desk", new Tier0.Request(r.kind(), r.title(), r.request(), r.components(),
				r.requestedBy(), List.of(new Tier0.Evidence("acme/ledger-jobs#42é", null)),
				Map.of("tdr", "TDR-001", "to", "Resolved", "note", "fixed"), "unattended"));
		assertThat(J.strings(accented, "warnings")).noneSatisfy((x) -> assertThat(x).contains("owner/repo"));
	}

	@Test
	void theStatusesAndDriversAreTheReleases() throws Exception {
		Map<String, Object> s = support();
		assertThat(Tier0.Policy.of(s).statuses()).isEqualTo(J.strings(s, "tdr_statuses")).isEqualTo(Tier0.STATUSES);
		assertThat(Tier0.Policy.of(s).drivers()).isEqualTo(J.strings(s, "tdr_drivers")).isEqualTo(Tier0.DRIVERS);

		// a release names its own vocabulary: a status or a driver it lists is one, another is not
		Map<String, Object> named = new LinkedHashMap<>(s);
		named.put("tdr_statuses", List.of("Open", "Parked", "Resolved"));
		named.put("tdr_drivers", List.of("Budget"));
		Tier0.Policy p = Tier0.Policy.of(named);
		assertThat(Tier0.validate(move("TDR-001", "Parked"), p)).isEmpty();
		assertThat(Tier0.validate(move("TDR-001", "In Progress"), p))
			.containsExactly("fields.to \"In Progress\" is not a status — one of Open · Parked · Resolved");
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("description", "d");
		f.put("impact", "i");
		f.put("solutions", List.of("s"));
		f.put("owner_technical", "o");
		f.put("driver", "Strategy");
		Tier0.Request workaround = new Tier0.Request("tdr-workaround", "A workaround", "r", List.of("Hub API"), null,
				List.of(new Tier0.Evidence("svc@1 a.go:1", null)), f);
		assertThat(Tier0.validate(workaround, p)).containsExactly("fields.driver \"Strategy\" is not one of Budget");

		// exported with v10.7.0: neither list, so the lists of that version
		Map<String, Object> before = new LinkedHashMap<>(s);
		before.remove("tdr_statuses");
		before.remove("tdr_drivers");
		assertThat(Tier0.Policy.of(before).statuses()).isEqualTo(Tier0.STATUSES);
		assertThat(Tier0.Policy.of(before).drivers()).isEqualTo(Tier0.DRIVERS);
	}

	static List<String> since(Map<String, List<String>> moves, String released, String to) {
		Tier0.Policy p = new Tier0.Policy(Map.of(), moves, Tier0.STATUSES, Tier0.DRIVERS, List.of());
		List<String> assumed = new java.util.ArrayList<>();
		List<String> errors = Tier0.against(move("TDR-001", to), p,
				new Tier0.Ledger(Map.of("TDR-001", released), java.util.Set.of(), "2.0.0"), assumed);
		return errors.isEmpty() ? assumed : errors;
	}

	@Test
	void aMoveValidFromAStatusSetSinceTheReleaseIsAssumedNeverOneThroughWontFix() throws Exception {
		// tdr-check.js MOVES, as the bundle lists them
		Map<String, List<String>> moves = new LinkedHashMap<>();
		J.map(support(), "tdr_moves").forEach((k, v) -> moves.put(k, J.strings(Map.of("v", v), "v")));
		assertThat(since(moves, "Open", "In Progress")).isEmpty();
		assertThat(since(moves, "Open", "Resolved"))
			.containsExactly("TDR-001 is Open in 2.0.0; this move assumes it is In Progress in main — the architecture repository checks it");
		assertThat(since(moves, "Planned", "Resolved"))
			.containsExactly("TDR-001 is Planned in 2.0.0; this move assumes it is In Progress in main — the architecture repository checks it");
		assertThat(since(moves, "Planned", "In Progress")).isEmpty();
		// nothing a Tier 0 write sets makes these valid
		assertThat(since(moves, "Resolved", "Open")).singleElement().asString()
			.isEqualTo("TDR-001: Resolved → Open is refused — no arrow from `Resolved` to `Open` — from `Resolved` the moves are none (terminal)");
		assertThat(since(moves, "Won't Fix", "In Progress")).singleElement().asString().startsWith("TDR-001: Won't Fix → In Progress is refused");
		assertThat(since(moves, "In Progress", "Open")).singleElement().asString().startsWith("TDR-001: In Progress → Open is refused");
		assertThat(since(moves, "Open", "Done")).singleElement().asString().contains("`Done` is not a status");

		Map<String, List<String>> other = new LinkedHashMap<>();
		other.put("Open", List.of("In Progress", "Won't Fix", "Planned"));
		other.put("Planned", List.of("Open", "Resolved"));
		other.put("In Progress", List.of("Resolved"));
		other.put("Resolved", List.of());
		other.put("Won't Fix", List.of());
		// several statuses it may be in: the ones the move is valid from, in the order the moves reach them
		assertThat(since(other, "Open", "Resolved")).containsExactly(
				"TDR-001 is Open in 2.0.0; this move assumes it is In Progress or Planned in main — the architecture repository checks it");
		// a move to the released status stays refused, even with a way back (Open ──► Planned ──► Open)
		assertThat(since(other, "Open", "Open")).singleElement().asString().contains("already `Open`");

		// never through Won't Fix, which only the architect sets: Open ──► Won't Fix ──► Planned is no way to Planned
		Map<String, List<String>> through = new LinkedHashMap<>(moves);
		through.put("Won't Fix", List.of("Planned"));
		assertThat(since(through, "Open", "Planned")).singleElement().asString().startsWith("TDR-001: Open → Planned is refused");
		// a release that is Won't Fix already may move on where the arrows let it
		assertThat(since(through, "Won't Fix", "Open")).containsExactly(
				"TDR-001 is Won't Fix in 2.0.0; this move assumes it is Planned in main — the architecture repository checks it");
	}

	@Test
	void withoutTier0InForceOrItsKindsNothingIsPreviewed() throws Exception {
		Tier0.Request r = move("TDR-001", "In Progress");
		assertThatThrownBy(() -> service((x) -> x.put("tier0", false)).preview("order-desk", r)).isInstanceOf(ToolException.class);
		assertThatThrownBy(() -> service((x) -> x.remove("tier0_kinds")).preview("order-desk", r))
			.hasMessageContaining("exported before the plugin's v10.7.0");
	}

}
