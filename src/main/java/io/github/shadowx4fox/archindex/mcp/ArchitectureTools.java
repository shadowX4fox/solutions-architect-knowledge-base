package io.github.shadowx4fox.archindex.mcp;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import io.github.shadowx4fox.archindex.build.BuildPackService;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.changes.ChangesService;
import io.github.shadowx4fox.archindex.index.SearchService;
import io.github.shadowx4fox.archindex.ledger.LedgerService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The MCP surface. Everything is a tool — every IDE client supports tools, while prompts and resources vary by client.
 * Three kinds: exact answers from the ledger, ranked search, and the build pack read part by part. Every answer is
 * JSON naming the project and the architecture version it came from.
 */
public class ArchitectureTools {

	private static final Logger log = LoggerFactory.getLogger(ArchitectureTools.class);

	private static final String VERSION = "Architecture version: omitted or \"latest\" = the newest released version; \"draft\" = the unreleased draft. Use the version in .architecture.json when building.";

	private static final String PROJECT = "Project key, as list_projects or .architecture.json names it";

	private static final String COMPONENT = "Component: its slug, canonical name or internal name, exactly";

	private final LedgerService ledger;

	private final SearchService search;

	private final BuildPackService packs;

	private final ChangesService changes;

	public ArchitectureTools(LedgerService ledger, SearchService search, BuildPackService packs, ChangesService changes) {
		this.ledger = ledger;
		this.search = search;
		this.packs = packs;
		this.changes = changes;
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

	@McpTool(name = "get_architecture", description = "One architecture version at a glance: status, language, systems, every component (slug, canonical name, internal name, C4 level, type, technology), chunk counts, which components have a build pack and why the others do not.")
	public CallToolResult getArchitecture(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> ledger.architecture(project, version));
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

	// ---- building one component

	@McpTool(name = "get_build_pack", description = "The build pack of one component for one architecture version — the index only: its parts (spec, contract assets, interface contracts, ADRs, standards, runtime view), the ordered build plan, interfaces both ways, flows and budgets, open questions. Then call get_build_instructions and get_build_part. Contracts are never searched: they are read whole.")
	public CallToolResult getBuildPack(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> packs.index(project, component, version));
	}

	@McpTool(name = "get_build_instructions", description = "BUILD.md of a component's build pack: the .architecture.json binding to write, the rules (contracts exactly, the architecture's names, never fill a gap, standards and ADRs, budgets, record the version), the plan, interfaces, budgets, open questions and the done list. Follow it.")
	public CallToolResult getBuildInstructions(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = VERSION, required = false) String version) {
		return answer(() -> packs.instructions(project, component, version));
	}

	@McpTool(name = "get_build_part", description = "One part of a build pack, byte for byte, by its id from get_build_pack (spec, asset:openapi.yaml, interface:a→b, adr:ADR-101, cross-cutting-standards, runtime-view, instructions). A part over 60,000 characters comes in pages: pass next_offset back as offset.")
	public CallToolResult getBuildPart(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = "Part id from get_build_pack") String part,
			@McpToolParam(description = VERSION, required = false) String version,
			@McpToolParam(description = "Character offset for the next page of a long part", required = false) Integer offset) {
		return answer(() -> packs.part(project, component, part, version, offset));
	}

	@McpTool(name = "get_asset", description = "One contract asset of a component (openapi.yaml, asyncapi.yaml, ddl.sql, schema.proto, *.avsc, schema.graphql, redis-key-schema.md), byte for byte — write it into the repository as is and generate code from it.")
	public CallToolResult getAsset(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT) String component,
			@McpToolParam(description = "Asset file name, e.g. openapi.yaml") String asset,
			@McpToolParam(description = VERSION, required = false) String version,
			@McpToolParam(description = "Character offset for the next page of a long asset", required = false) Integer offset) {
		return answer(() -> packs.asset(project, component, asset, version, offset));
	}

	@McpTool(name = "what_changed", description = "What a newer architecture release changed for one component since the version it was built against: facts, contracts, interfaces, integrations, flows and budgets, ADRs, open questions — and whether code must move (action_required). Use after a release, then update the service and .architecture.json.")
	public CallToolResult whatChanged(@McpToolParam(description = PROJECT) String project,
			@McpToolParam(description = COMPONENT + " (omit for every container that changed)", required = false) String component,
			@McpToolParam(description = "The version built against — the one in .architecture.json") String from,
			@McpToolParam(description = "The newer version (default: the latest released)", required = false) String to) {
		return answer(() -> changes.whatChanged(project, component, from, to));
	}

}
