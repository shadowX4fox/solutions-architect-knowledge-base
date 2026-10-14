package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.index.Indexer;

/**
 * The server end to end, as CI and an IDE meet it: bundles published over HTTP with the pipeline token, indexed in
 * memory with a stand-in embedding model, and every tool called through the real MCP endpoint (Streamable HTTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"archindex.store=memory", "archindex.ingest-token=ci-token", "archindex.mcp-token=ide-token",
		"spring.ai.model.embedding=none" })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerTest {

	@TempDir
	static Path data;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("archindex.data-dir", () -> data.toString());
	}

	@TestConfiguration
	static class Model {

		@Bean
		@Primary
		EmbeddingModel embeddingModel() {
			return new Fixtures.HashingEmbeddingModel();
		}

	}

	@LocalServerPort
	int port;

	@Autowired
	Indexer indexer;

	@Autowired
	EmbeddingModel model;

	final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> post(String path, byte[] body, String token) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Content-Type", "application/gzip")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private void awaitReady(String project, String slot) throws Exception {
		for (int i = 0; i < 100; i++) {
			Indexer.SlotState s = indexer.state(project, slot);
			if (s != null && s.status() == Indexer.Status.READY) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("not indexed: " + indexer.state(project, slot));
	}

	// ---- MCP over Streamable HTTP

	private String session;

	private int rpcId;

	private Map<String, Object> rpc(String method, Map<String, Object> params) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("Authorization", "Bearer ide-token")
			.POST(HttpRequest.BodyPublishers.ofString(J.write(Map.of("jsonrpc", "2.0", "id", ++rpcId, "method", method, "params", params))));
		if (session != null) {
			b.header("Mcp-Session-Id", session);
		}
		HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		r.headers().firstValue("Mcp-Session-Id").ifPresent((s) -> session = s);
		String body = r.body().trim();
		if (!body.startsWith("{")) {   // an SSE answer: the JSON is on the data: line
			body = body.lines().filter((l) -> l.startsWith("data:")).map((l) -> l.substring(5).trim()).reduce((a, x) -> x).orElseThrow();
		}
		return J.obj(body);
	}

	private void initialize() throws Exception {
		if (session != null) {
			return;
		}
		Map<String, Object> r = rpc("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
				"clientInfo", Map.of("name", "test-ide", "version", "1")));
		assertThat(J.str(J.map(J.map(r, "result"), "serverInfo"), "name")).isEqualTo("architecture");
		HttpRequest n = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("Authorization", "Bearer ide-token")
			.header("Mcp-Session-Id", session)
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
			.build();
		http.send(n, HttpResponse.BodyHandlers.discarding());
	}

	/** Calls a tool; returns its text parsed as JSON when it is JSON. */
	private Map<String, Object> tool(String name, Map<String, Object> args, boolean expectError) throws Exception {
		initialize();
		Map<String, Object> r = J.map(rpc("tools/call", Map.of("name", name, "arguments", args)), "result");
		assertThat(Boolean.TRUE.equals(r.get("isError"))).as(J.write(r)).isEqualTo(expectError);
		String text = J.str(J.maps(r, "content").get(0), "text");
		return text.startsWith("{") ? J.obj(text) : Map.of("text", text);
	}

	private Map<String, Object> tool(String name, Map<String, Object> args) throws Exception {
		return tool(name, args, false);
	}

	@Test
	@Order(1)
	void publishingFollowsTheContractTheCiScriptCalls() throws Exception {
		byte[] search130 = Fixtures.tgz(Fixtures.bundle("1.3.0-search"));
		assertThat(post("/bundles/order-desk/1.3.0?channel=released", search130, null).statusCode()).isEqualTo(401);
		assertThat(post("/bundles/order-desk/1.3.0?channel=released", search130, "ci-token").statusCode()).isEqualTo(201);
		assertThat(post("/bundles/order-desk/1.3.0?channel=released", Fixtures.tgz(Fixtures.bundle("1.3.0")), "ci-token").statusCode()).isEqualTo(200);
		HttpResponse<String> mismatch = post("/bundles/order-desk/9.9.9?channel=released", Fixtures.tgz(Fixtures.bundle("1.4.0")), "ci-token");
		assertThat(mismatch.statusCode()).isEqualTo(400);
		assertThat(post("/bundles/order-desk/1.4.0?channel=released", Fixtures.tgz(Fixtures.bundle("1.4.0")), "ci-token").statusCode()).isEqualTo(201);
		assertThat(post("/bundles/order-desk/1.4.0?channel=draft", Fixtures.tgz(Fixtures.bundle("draft")), "ci-token").statusCode()).isEqualTo(201);
		HttpResponse<byte[]> before = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bundles/order-desk/before/1.4.0"))
			.header("Authorization", "Bearer ci-token").build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(before.statusCode()).isEqualTo(200);
		assertThat(before.headers().firstValue("X-Architecture-Version")).contains("1.3.0");
		Path got = data.resolve("roundtrip");
		io.github.shadowx4fox.archindex.bundle.Tgz.extract(new java.io.ByteArrayInputStream(before.body()), got, 50_000_000);
		assertThat(io.github.shadowx4fox.archindex.bundle.BundleChecker.check(got).findings()).isEmpty();
		assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bundles/order-desk/before/1.3.0"))
			.header("Authorization", "Bearer ci-token").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
		awaitReady("order-desk", "1.3.0");
		awaitReady("order-desk", "1.4.0");
		awaitReady("order-desk", "draft");
		// 1.4.0 re-embedded only the chunks its edits changed
		Indexer.SlotState s = indexer.state("order-desk", "1.4.0");
		assertThat(s.reused()).isGreaterThan(0);
		assertThat(s.embedded()).isLessThan(s.chunks());
	}

	@Test
	@Order(2)
	void theMcpEndpointNeedsItsTokenAndListsEveryTool() throws Exception {
		HttpResponse<String> anon = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(anon.statusCode()).isEqualTo(401);
		initialize();
		List<String> names = J.maps(J.map(rpc("tools/list", Map.of()), "result"), "tools").stream().map((t) -> J.str(t, "name")).toList();
		assertThat(names).containsExactlyInAnyOrder("list_projects", "get_architecture", "get_component", "list_integrations",
				"list_flows", "get_flow", "list_decisions", "search_architecture", "get_chunk", "get_build_pack",
				"get_build_instructions", "get_build_part", "get_asset", "what_changed");
	}

	@Test
	@Order(3)
	void exactAnswersComeFromTheLedger() throws Exception {
		String listed = J.str(tool("list_projects", Map.of()), "text");
		assertThat(listed).contains("\"project\":\"order-desk\"", "\"latest\":\"1.4.0\"", "\"draft\":\"1.4.0\"");
		Map<String, Object> hub = tool("get_component", Map.of("project", "order-desk", "component", "od-hub-api"));
		assertThat(hub).containsEntry("slug", "hub-api").containsEntry("name", "Hub API").containsEntry("version", "1.4.0");
		assertThat(J.strings(hub, "technology")).containsExactly("Spring Boot", "Kafka");
		assertThat(J.maps(hub, "integrations")).extracting((i) -> J.str(i, "id")).containsExactly("I-01", "I-02");
		assertThat(J.str(hub, "build_pack")).startsWith("available");
		assertThat(J.str(tool("get_component", Map.of("project", "order-desk", "component", "ledger-store")), "build_pack")).isEqualTo("none: not in the kit");
		// an exact name only: a partial name is an error that lists the real ones
		assertThat(J.str(tool("get_component", Map.of("project", "order-desk", "component", "Hub"), true), "text")).contains("Hub API (hub-api)");
		Map<String, Object> flow = tool("get_flow", Map.of("project", "order-desk", "flow", "UC-01", "version", "1.3.0"));
		assertThat(J.maps(flow, "phases")).extracting((p) -> J.str(p, "latency_budget")).containsExactly("120 ms", "80 ms");
		assertThat(J.maps(tool("list_decisions", Map.of("project", "order-desk", "kind", "tdr")), "tdrs")).extracting((t) -> J.str(t, "id")).containsExactly("TDR-001");
	}

	@Test
	@Order(4)
	void searchFindsExactIdentifiersAndReturnsWhereEachHitComesFrom() throws Exception {
		Map<String, Object> r = tool("search_architecture", Map.of("query", "ADR-101 idempotency key", "project", "order-desk"));
		List<Map<String, Object>> hits = J.maps(r, "hits");
		assertThat(hits).isNotEmpty();
		assertThat(hits.get(0)).containsEntry("version", "1.4.0");
		assertThat(hits.stream().map((h) -> J.str(h, "file")).toList()).contains("adr/ADR-101-idempotency-keys.md");
		assertThat(hits.get(0)).containsKeys("chunk_id", "anchor", "lines", "text");
		Map<String, Object> onlyAdrs = tool("search_architecture", Map.of("query", "idempotency", "project", "order-desk", "kinds", List.of("adr")));
		assertThat(J.maps(onlyAdrs, "hits")).allSatisfy((h) -> assertThat(h).containsEntry("kind", "adr"));
		Map<String, Object> draft = tool("search_architecture", Map.of("query", "phone e-mail orders", "project", "order-desk", "version", "draft"));
		assertThat(J.maps(draft, "hits").get(0)).containsEntry("channel", "draft");
		String chunkId = J.str(hits.get(0), "chunk_id");
		assertThat(tool("get_chunk", Map.of("project", "order-desk", "chunkId", chunkId))).containsEntry("chunk_id", chunkId);
		// the query went to the model with EmbeddingGemma's query prompt; the documents with their title prompt
		List<String> seen = ((Fixtures.HashingEmbeddingModel) model).seen;
		assertThat(seen).anyMatch((t) -> t.startsWith("task: search result | query: ADR-101"));
		assertThat(seen).anyMatch((t) -> t.startsWith("title: Idempotency keys on every write | text: Order Desk"));
	}

	@Test
	@Order(5)
	void aComponentIsBuiltFromItsPackPartByPart() throws Exception {
		Map<String, Object> pack = tool("get_build_pack", Map.of("project", "order-desk", "component", "hub-api", "version", "1.4.0"));
		assertThat(pack).containsEntry("architecture_version", "1.4.0");
		assertThat(J.maps(pack, "plan")).extracting((s) -> J.str(s, "id")).startsWith("spec", "contracts", "data", "interfaces");
		String build = J.str(tool("get_build_instructions", Map.of("project", "order-desk", "component", "Hub API", "version", "1.4.0")), "content");
		assertThat(build).startsWith("# Build Hub API").contains("\"architecture_version\": \"1.4.0\"");
		Map<String, Object> openapi = tool("get_asset", Map.of("project", "order-desk", "component", "hub-api", "asset", "openapi.yaml", "version", "1.4.0"));
		assertThat(J.str(openapi, "content")).isEqualTo(java.nio.file.Files.readString(Fixtures.bundle("1.4.0").resolve("buildpacks/hub-api/assets/openapi.yaml")));
		assertThat(J.str(tool("get_build_part", Map.of("project", "order-desk", "component", "hub-api", "part", "interface:web-app→hub-api", "version", "1.4.0")), "content"))
			.contains("Interface Contract: Web App → Hub API");
		// no pack, no guess: the error says why and which versions have one
		assertThat(J.str(tool("get_build_pack", Map.of("project", "order-desk", "component", "web-app"), true), "text")).contains("kit-version 1.2.0");
		assertThat(J.str(tool("get_build_part", Map.of("project", "order-desk", "component", "hub-api", "part", "nope"), true), "text")).contains("asset:openapi.yaml");
	}

	@Test
	@Order(6)
	void whatChangedTellsTheDeveloperWhatToUpdate() throws Exception {
		Map<String, Object> r = tool("what_changed", Map.of("project", "order-desk", "component", "hub-api", "from", "1.3.0"));
		assertThat(r).containsEntry("source", "changes.json of 1.4.0");
		assertThat(J.strings(r, "action_required")).containsExactly("hub-api");
		assertThat(J.str(r, "summary")).contains("- contracts: changed openapi.yaml", "phase 2 latency_budget 80 ms → 90 ms");
		// a pair no export shipped is computed the same way
		Map<String, Object> computed = tool("what_changed", Map.of("project", "order-desk", "from", "1.3.0", "to", "draft"));
		assertThat(computed).containsEntry("source", "computed from the two bundles");
		assertThat(J.str(tool("what_changed", Map.of("project", "order-desk", "from", "1.4.0", "to", "1.3.0"), true), "text")).contains("is newer than");
	}

}
