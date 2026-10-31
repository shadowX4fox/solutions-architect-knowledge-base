package io.github.shadowx4fox.archindex.mcp;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import io.github.shadowx4fox.archindex.build.BuildPackService;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.changes.ChangesService;
import io.github.shadowx4fox.archindex.escalation.Escalate;
import io.github.shadowx4fox.archindex.escalation.EscalationService;
import io.github.shadowx4fox.archindex.escalation.Tier0;
import io.github.shadowx4fox.archindex.escalation.Tier0Service;
import io.github.shadowx4fox.archindex.index.SearchService;
import io.github.shadowx4fox.archindex.ledger.DocumentService;
import io.github.shadowx4fox.archindex.ledger.LedgerService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The MCP surface. Everything is a tool — every IDE client supports tools, while prompts and resources vary by client.
 * Exact answers from the ledger, ranked search, the build pack read part by part, and a RUN team's escalation. Every
 * answer is JSON naming the project and the architecture version it came from.
 */
public class ArchitectureTools {

	private static final Logger log = LoggerFactory.getLogger(ArchitectureTools.class);

	private static final String VERSION = "Architecture version: omitted or \"latest\" = the newest released version; \"draft\" = the unreleased draft. Use the version in .architecture.json when building.";

	private static final String PROJECT = "Project key, as list_projects or .architecture.json names it";

	private static final String SUPERVISION = "attended (the default: a person works with you) or unattended (a build no person attends — a CI job, a platform's build agent — as .architecture.json's supervision declares)";

	private static final String COMPONENT = "Component: its slug, canonical name or internal name, exactly";

	private final LedgerService ledger;

	private final SearchService search;

	private final BuildPackService packs;

	private final ChangesService changes;

	private final DocumentService documents;

	private final EscalationService escalations;

	private final Tier0Service tier0;

	public ArchitectureTools(LedgerService ledger, SearchService search, BuildPackService packs, ChangesService changes,
			DocumentService documents, EscalationService escalations, Tier0Service tier0) {
		this.tier0 = tier0;
		this.ledger = ledger;
		this.search = search;
		this.packs = packs;
		this.changes = changes;
		this.documents = documents;
		this.escalations = escalations;
	}

	static CallToolResult answer(Supplier<Object> s) {
		try {
			Object v = s.get();
			return CallToolResult.builder().addTextContent(v instanceof String str ? str : J.write(v)).build();
		}
		catch (ToolException e) {
			return CallToolResult.builder().addTextContent(e.getMessage()).isError(true).build();
		}
		catch (RuntimeException e) {
			log.error("tool failed", e);
			return CallToolResult.builder().addTextContent("server error: " + e.getMessage()).isError(true).build();
		}
	}

	// ---- orientation

	@McpTool(name = "list_projects", description = "The architectures this server holds: key, name, released versions, the latest, a draft if any, the components with a build pack, whether search is ready. Call first when no .architecture.json names the project.")
	public CallToolResult listProjects() {
		return answer(ledger::projects);
	}

	@McpTool(name = "get_architecture", description = "One architecture version at a glance: status, language, systems, every component (slug, canonical name, internal name, C4 level, type, technology), chunk counts, which components have a build pack and why the others do not — and support, the RUN-team policy: whether Tier 0 applies, the writes of Tier 0 (tier0_writes), which are requested with preview_tier0 and never made from the service repository, because the architecture repository writes them, the kinds an unattended build keys itself (tier0_auto), the closed list of escalation triggers, the routes and the escalations already open. Before proposing a change, check it against triggers; a crossed trigger is escalated (preview_escalation), never applied. A question that blocks the build and only the architect can answer (a contract with nothing to implement, an interface whose authentication is not documented, a Proposed decision the code depends on) is escalated the same way.")
	public CallToolResult getArchitecture(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> {
			Map<String, Object> m = ledger.architecture(project, version);
			if (m.get("support") instanceof Map<?, ?>) {
				m.put("escalation_filing", escalations.filing(J.str(m, "project")));
			}
			return m;
		});
	}

	// ---- exact answers from the ledger

	@McpTool(name = "get_component", description = "Everything the ledger states about one component: type, technology, internal name, versions, the docs/05 integrations naming it, the flows and phases it takes part in with latency budgets, and whether a build pack exists.")
	public CallToolResult getComponent(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> ledger.component(project, component, version));
	}

	@McpTool(name = "list_integrations", description = "The integration inventory (docs/05 I-nn rows: from, to, protocol, pattern), all of it or the rows naming one component. Complete — use it rather than search for 'what talks to X'.")
	public CallToolResult listIntegrations(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT + " (omit for every row)", required = false) String component,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> ledger.integrations(project, component, version));
	}

	@McpTool(name = "list_flows", description = "The documented flows (use cases) with their phases, hops and latency budgets — all of them, or the ones a component takes part in.")
	public CallToolResult listFlows(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT + " (omit for every flow)", required = false) String component,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> ledger.flows(project, component, version));
	}

	@McpTool(name = "get_flow", description = "One flow by its number, name or use case id (UC-nn): trigger, latency target, governing ADRs, each phase with hops and budget.")
	public CallToolResult getFlow(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "Flow number, flow name or use case id") String flow,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> ledger.flow(project, flow, version));
	}

	@McpTool(name = "list_decisions", description = "Architecture Decision Records (kind adr) or Technical Debt Records (kind tdr) with id, title and status, optionally filtered by status (Accepted, Proposed, Open, …).")
	public CallToolResult listDecisions(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "adr (default) or tdr", required = false) String kind,
			@McpToolParam(description = "Only records with this status", required = false) String status,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> ledger.decisions(project, kind, status, version));
	}

	// ---- ranked search

	@McpTool(name = "search_architecture", description = "Search the architecture's text for reasons and explanations ('why is this call asynchronous?', 'how is idempotency handled?'). Hybrid: meaning plus exact identifiers such as ADR-112 or I-07. Each hit gives its file, lines, anchor and version to cite. For lists and relations use the ledger tools instead. Without a project it searches the latest release of every project.")
	public CallToolResult searchArchitecture(@McpToolParam(description = "The question or terms") String query,
			@McpToolParam(description = PROJECT + " (omit to search every project)", required = false) String project,
			@McpToolParam(description = VERSION, required = false) String version,
			@McpToolParam(description = "Only these chunk kinds: overview, section, principle, flow, phase, integration, component, adr, tdr", required = false) List<String> kinds,
			@McpToolParam(description = COMPONENT + " — only chunks about it", required = false) String component,
			@McpToolParam(description = "Also search the project's unreleased draft", required = false) Boolean includeDraft,
			@McpToolParam(description = "Hits to return (default 8, at most 25)", required = false) Integer limit) {
		return answer(() -> search.search(query, project, version, kinds, component, Boolean.TRUE.equals(includeDraft), limit));
	}

	@McpTool(name = "get_chunk", description = "The whole text of one chunk a search hit named (search returns at most the first 2,000 characters).")
	public CallToolResult getChunk(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "chunk_id from a search hit") String chunkId,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> ledger.chunk(project, chunkId, version));
	}

	@McpTool(name = "get_document", description = "One whole document — an ADR, a TDR, a docs/ section file, a component descriptor — in one call, rebuilt from its chunks in line order, with the lines it covers and the gaps. Name it by the file a search hit gave, an ADR or TDR id (ADR-109), or a component. Use it instead of reading a document chunk by chunk. A document over 60,000 characters comes in pages: pass next_offset back as offset.")
	public CallToolResult getDocument(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "A file path from a search hit (adr/ADR-109-….md, docs/05-integration-points.md), an ADR or TDR id, or a component") String document,
			@McpToolParam(description = VERSION, required = false) String version,
			@McpToolParam(description = "Character offset for the next page of a long document", required = false) Integer offset) {
		return answer(() -> documents.document(project, document, version, offset));
	}

	// ---- building one component

	@McpTool(name = "get_build_pack", description = "The build pack of one component for one architecture version — the index only: its parts (spec, security obligations, contract assets, dependency contracts — the API it calls and the events it reads, whole, from the component at the other end, kept at architecture/deps/<provider>/ —, interface contracts, ADRs, standards, runtime view), the ordered build plan, interfaces both ways, flows and budgets, open questions. Each ADR part has a scope: own when this component's descriptor names it (the code carries it out), context when only an interface contract, a flow or the security section brings it (what the other side does, not an obligation of this code) — and via, every origin that brought it in. Each part has its sha256 and, when the server has a public URL, a download_url serving its bytes as stored (the MCP bearer token). Then call get_build_instructions and get_build_part. Contracts are never searched: they are read whole.")
	public CallToolResult getBuildPack(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> packs.index(project, component, version));
	}

	@McpTool(name = "get_build_instructions", description = "BUILD.md of a component's build pack: the .architecture.json binding to write, the rules (contracts exactly, the architecture's names, never fill a gap, security never a gap — deny by default, an interface without documented authentication fails closed —, standards and ADRs, budgets, record the version), the plan, interfaces, budgets, open questions and the done list. Follow it.")
	public CallToolResult getBuildInstructions(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> packs.instructions(project, component, version));
	}

	@McpTool(name = "get_build_part", description = "One part of a build pack, byte for byte, by its id from get_build_pack (spec, security, asset:openapi.yaml, dependency:ledger-store/openapi.yaml, interface:a→b, adr:ADR-101, cross-cutting-standards, runtime-view, instructions). A part over 60,000 characters comes in pages: pass next_offset back as offset. To save a part to disk, prefer its download_url (curl with the MCP bearer token, then check the sha256); the content is for reading — when there is no download_url, write the content of every page unchanged.")
	public CallToolResult getBuildPart(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = "Part id from get_build_pack") String part,
			@McpToolParam(description = VERSION, required = false) String version,
			@McpToolParam(description = "Character offset for the next page of a long part", required = false) Integer offset) {
		return answer(() -> packs.part(project, component, part, version, offset));
	}

	@McpTool(name = "get_asset", description = "One contract asset of a component (openapi.yaml, asyncapi.yaml, ddl.sql, schema.proto, *.avsc, schema.graphql, redis-key-schema.md), byte for byte. To save it into the repository, prefer its download_url (curl with the MCP bearer token, then check the sha256); the content is for reading — when there is no download_url, write the content of every page unchanged. Generate code from the saved file.")
	public CallToolResult getAsset(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = "Asset file name, e.g. openapi.yaml") String asset,
			@McpToolParam(description = VERSION, required = false) String version,
			@McpToolParam(description = "Character offset for the next page of a long asset", required = false) Integer offset) {
		return answer(() -> packs.asset(project, component, asset, version, offset));
	}

	@McpTool(name = "what_changed", description = "What changed for one component since the version and the build pack revision it was built against: facts, contracts, dependency contracts (a provider's contract moved: regenerate the client), interfaces, integrations, flows and budgets, ADRs, security obligations, open questions, technical debt (the TDRs its descriptor names: added, moved, dropped — listed, never an action), restamped (parts that moved only their version stamp, link targets or debt table — never an action) — and whether code must move (action_required). Use after a release, or when get_build_pack returns another pack_revision for the same version; then update the service and .architecture.json.")
	public CallToolResult whatChanged(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT + " (omit for every container that changed; required with from_revision)", required = false) String component,
			@McpToolParam(description = "The version built against — the one in .architecture.json") String from,
			@McpToolParam(description = "The pack_revision built from — the one in .architecture.json. With it, the build pack replacements of that version made since come first", required = false) String from_revision,
			@McpToolParam(description = "The newer version (default: the latest released; the same version for the replacements within it)", required = false) String to) {
		return answer(() -> changes.whatChanged(project, component, from, from_revision, to));
	}

	// ---- a RUN team's escalation

	@McpTool(name = "preview_escalation", description = "Two cases become an escalation for a human architect. (a) A change that crosses an escalation trigger (get_architecture's support.triggers) is never applied. (b) A question that only the architect can answer and that blocks the build: a contract with nothing to implement, an interface whose authentication is not documented, a Proposed decision the code depends on. For (b), send the questions in questions, the trigger each one touches (an authentication or authorization gap is security-iam; a gap no other trigger names is low-confidence) with its evidence (the build-pack part and the line), and route tier-1. Either way it is an escalation filed as an issue on the architecture's git host. This returns that issue exactly as it would be opened — title [ESC-?] …, labels, body — checked against the latest release's support policy, the preview_id, the escalations already open, and file_with: the commands that file it with the developer's own access. Nothing is written. Show the user the issue and ask; only after a yes, follow file_with.steps, then call record_escalation. A build no person attends (supervision: \"unattended\" in .architecture.json) passes supervision unattended: it files with its own identity, without a yes — an escalation writes nothing — and lists the issue in its pull request.")
	public CallToolResult previewEscalation(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "Short title, one line") String title,
			@McpToolParam(description = "What the developer asked to build or change, in their words — the task that led to the escalation. Never their answer to a question of yours (a yes to escalate is not the request).") String request,
			@McpToolParam(description = "Trigger ids crossed, from support.triggers") List<String> triggers,
			@McpToolParam(description = "Per trigger: where it shows (a file and line, or a chunk id) and a note. Every trigger but low-confidence needs one", required = false) List<Escalate.Evidence> evidence,
			@McpToolParam(description = "The change the agent would have made, one line per item — not applied", required = false) List<String> proposal,
			@McpToolParam(description = "Open questions for the architect, one per item", required = false) List<String> questions,
			@McpToolParam(description = "Components concerned, by canonical name", required = false) List<String> components,
			@McpToolParam(description = "Integration ids (I-nn)", required = false) List<String> integrations,
			@McpToolParam(description = "ADR ids", required = false) List<String> adrs,
			@McpToolParam(description = "TDR ids", required = false) List<String> tdrs,
			@McpToolParam(description = "Who asked: the developer or the team", required = false) String requested_by,
			@McpToolParam(description = "tier-1 (SA office hours, the default) or tier-2 (design authority, CHANGE work)", required = false) String route,
			@McpToolParam(description = SUPERVISION, required = false) String supervision) {
		return answer(() -> escalations.preview(project, new Escalate.Request(title, request, triggers, evidence, proposal,
				questions, components, integrations, adrs, tdrs, requested_by, route), supervision));
	}

	@McpTool(name = "record_escalation", description = "After the user said yes and the issue from preview_escalation was filed on the git host: records which issue it became, so the escalation is counted. Call it once per filed issue, with project, preview_id and url (the issue's URL).")
	public CallToolResult recordEscalation(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "The preview_id preview_escalation returned") String preview_id,
			@McpToolParam(description = "The URL of the filed issue") String url) {
		return answer(() -> escalations.record(project, preview_id, url));
	}

	// ---- a RUN team's Tier 0 request

	@McpTool(name = "preview_tier0", description = "A Tier 0 write — a workaround recorded as a TDR (tdr-workaround), a TDR status move (tdr-status), a project ADR in Proposed (adr-proposed), a doc fix that changes no fact (doc-fix); get_architecture's support.tier0_kinds lists them with their required fields — is requested, never made from the service repository: the architecture repository writes the record itself (architecture-tier0.yml) after re-checking the policy. This returns the request as the issue it would be opened — title [T0-?] …, labels architecture-tier0 and tier0:<kind>, body — checked against the latest release (Tier 0 in force, the kind and its fields, canonical component names, the TDR and its move, the ADRs; a move to Won't Fix is always refused; a move valid only from a status a Tier 0 write may have set since the release is previewed, and next says what it assumes; a TDR or ADR created since the release is checked on the project's draft, and next says so), the preview_id and file_with: the commands that file it with the developer's own access. Nothing is written. Show the user the issue and ask; only after a yes, follow file_with.steps, then call record_tier0. A build no person attends (supervision: \"unattended\" in .architecture.json) passes supervision unattended: the issue carries only tier0:<kind>, never architecture-tier0 — a person applies that label, and that is the approval; never apply it yourself — and is filed with the build's own identity and listed in its pull request. A kind in support.tier0_auto needs no person: the architecture repository keys it itself when the build's identity is one of its trusted agents — the agent still never applies the label. A move to Resolved then names what resolved it, the build's pull request as owner/repo#N or a commit as owner/repo@sha; file it now: the architecture repository writes it once that is merged.")
	public CallToolResult previewTier0(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "One of support.tier0_kinds: tdr-workaround, tdr-status, adr-proposed, doc-fix") String kind,
			@McpToolParam(description = "Short title, one line") String title,
			@McpToolParam(description = "What the developer asked to build or change, in their words — the task that led to the request. Never their answer to a question of yours (a yes to file is not the request).") String request,
			@McpToolParam(description = "Components concerned, by canonical name", required = false) List<String> components,
			@McpToolParam(description = "Who asked: the developer or the team", required = false) String requested_by,
			@McpToolParam(description = "Each { where, note }: where is the service repository, the commit and the file:line. Required for tdr-workaround and tdr-status", required = false) List<Tier0.Evidence> evidence,
			@McpToolParam(description = "The fields of the kind, in the order to be shown. tdr-workaround: description, impact, solutions (list), owner_technical; optional driver (Strategy · Architecture · Talent · Process), owner_business, adrs. tdr-status: tdr (TDR-NNN), to, note; target_date when to is Planned. adr-proposed: context, decision, consequences (list); optional alternatives. doc-fix: file (under docs/), line, before, after (one line each, different)") Map<String, Object> fields,
			@McpToolParam(description = SUPERVISION, required = false) String supervision) {
		return answer(() -> tier0.preview(project, new Tier0.Request(kind, title, request, components, requested_by, evidence, fields, supervision)));
	}

	@McpTool(name = "record_tier0", description = "After the user said yes and the issue from preview_tier0 was filed on the git host: records which issue it became, so the Tier 0 request is counted. Call it once per filed issue, with project, preview_id and url (the issue's URL).")
	public CallToolResult recordTier0(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = "The preview_id preview_tier0 returned") String preview_id,
			@McpToolParam(description = "The URL of the filed issue") String url) {
		return answer(() -> tier0.record(project, preview_id, url));
	}

}
