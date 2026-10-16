package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.index.MemoryVectorIndex;
import io.github.shadowx4fox.archindex.index.VectorIndex;

/**
 * Two instances on one data directory and one index, as behind a load balancer: the writer publishes and indexes, the
 * reader answers from what the writer wrote and never embeds a document. The index is an in-memory one both share —
 * Qdrant's part, the marks, is held to the same contract by QdrantTest.
 */
class ScaleTest {

	@TempDir
	static Path data;

	/** The collection both instances reach. */
	static final SharedIndex INDEX = new SharedIndex();

	static ConfigurableApplicationContext writer;

	static ConfigurableApplicationContext reader;

	static final HttpClient http = HttpClient.newHttpClient();

	static class SharedIndex extends MemoryVectorIndex {

		@Override
		public boolean shared() {
			return true;
		}

	}

	@Configuration
	static class Stand {

		@Bean
		@Primary
		EmbeddingModel embeddingModel() {
			return new Fixtures.HashingEmbeddingModel();
		}

		@Bean
		@Primary
		VectorIndex sharedIndex() {
			return INDEX;
		}

	}

	static ConfigurableApplicationContext start(String role) {
		// as arguments: they win over application.yaml, as a deployment's environment does
		return new SpringApplicationBuilder(ArchIndexApplication.class, Stand.class).run("--server.port=0",
				"--archindex.role=" + role, "--archindex.store=memory", "--archindex.data-dir=" + data,
				"--archindex.ingest-token=ci-token", "--archindex.mcp-token=ide-token", "--archindex.public-url=http://alia.test",
				"--archindex.refresh-interval=200ms", "--archindex.retain-replaced=10m", "--spring.ai.model.embedding=none");
	}

	@BeforeAll
	static void up() {
		writer = start("all");
		reader = start("reader");
	}

	@AfterAll
	static void down() {
		if (reader != null) {
			reader.close();
		}
		if (writer != null) {
			writer.close();
		}
	}

	static int port(ConfigurableApplicationContext c) {
		return ((WebServerApplicationContext) c).getWebServer().getPort();
	}

	static HttpResponse<String> publish(ConfigurableApplicationContext c, String version, String bundle) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port(c) + "/bundles/order-desk/" + version + "?channel=released"))
			.header("Authorization", "Bearer ci-token")
			.header("Content-Type", "application/gzip")
			.POST(HttpRequest.BodyPublishers.ofByteArray(Fixtures.tgz(Fixtures.bundle(bundle))))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** A tool call with no initialize and no session, as a load balancer may send it to any instance. */
	static Map<String, Object> tool(ConfigurableApplicationContext c, String name, Map<String, Object> args) throws Exception {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port(c) + "/mcp"))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("Authorization", "Bearer ide-token")
			.POST(HttpRequest.BodyPublishers.ofString(J.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
					"params", Map.of("name", name, "arguments", args)))))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		String body = r.body().trim();
		if (!body.startsWith("{")) {
			body = body.lines().filter((l) -> l.startsWith("data:")).map((l) -> l.substring(5).trim()).reduce((a, x) -> x).orElseThrow();
		}
		Map<String, Object> result = J.map(J.obj(body), "result");
		String text = J.str(J.maps(result, "content").get(0), "text");
		return Map.of("error", Boolean.TRUE.equals(result.get("isError")), "text", text,
				"json", text.startsWith("{") ? J.obj(text) : Map.of());
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> json(Map<String, Object> r) {
		return (Map<String, Object>) r.get("json");
	}

	static <T> T await(String what, Callable<T> probe, java.util.function.Predicate<T> done) throws Exception {
		T last = null;
		for (int i = 0; i < 100; i++) {
			last = probe.call();
			if (done.test(last)) {
				return last;
			}
			Thread.sleep(100);
		}
		throw new AssertionError(what + ": " + last);
	}

	@Test
	void theReaderServesWhatTheWriterPublishedAndNeverIndexes() throws Exception {
		// a reader refuses a publish: the pipeline goes to the writer
		HttpResponse<String> refused = publish(reader, "1.3.0", "1.3.0");
		assertThat(refused.statusCode()).isEqualTo(403);
		assertThat(refused.body()).contains("publish to the writer");

		assertThat(publish(writer, "1.3.0", "1.3.0").statusCode()).isEqualTo(201);
		assertThat(publish(writer, "1.4.0", "1.4.0").statusCode()).isEqualTo(201);

		// the reader reloads on its own, and searches once the writer has marked the slot indexed
		await("the reader lists 1.4.0", () -> tool(reader, "list_projects", Map.of()).get("text").toString(),
				(t) -> t.contains("\"latest\":\"1.4.0\""));
		Map<String, Object> hits = await("the reader searches 1.4.0",
				() -> tool(reader, "search_architecture", Map.of("query", "ADR-101 idempotency key", "project", "order-desk")),
				(r) -> !(Boolean) r.get("error") && !J.maps(json(r), "hits").isEmpty());
		assertThat(J.maps(json(hits), "hits").get(0)).containsEntry("version", "1.4.0");

		// both answer the same pack revision
		Map<String, Object> args = Map.of("project", "order-desk", "component", "hub-api", "version", "1.4.0");
		String built = J.str(json(tool(writer, "get_build_pack", args)), "pack_revision");
		assertThat(J.str(json(tool(reader, "get_build_pack", args)), "pack_revision")).isEqualTo(built);

		// the kit regenerated: the writer replaces the pack; the reader follows, and serves the new bytes
		HttpResponse<String> revised = publish(writer, "1.4.0", "1.4.0-revised");
		assertThat(revised.statusCode()).as(revised.body()).isEqualTo(200);
		String now = J.str(json(tool(writer, "get_build_pack", args)), "pack_revision");
		assertThat(now).isNotEqualTo(built);
		Map<String, Object> pack = await("the reader serves the replaced pack", () -> json(tool(reader, "get_build_pack", args)),
				(p) -> now.equals(J.str(p, "pack_revision")));
		Map<String, Object> security = J.maps(pack, "parts").stream().filter((x) -> "security".equals(J.str(x, "id"))).findFirst().orElseThrow();
		HttpResponse<byte[]> got = http.send(HttpRequest.newBuilder(URI.create(J.str(security, "download_url")
			.replace("http://alia.test", "http://localhost:" + port(reader)))).header("Authorization", "Bearer ide-token").build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertThat(got.statusCode()).isEqualTo(200);
		assertThat(J.sha256(got.body())).isEqualTo(J.str(security, "sha256"));
		assertThat(got.headers().firstValue("X-Pack-Revision")).contains(now);
		// what_changed on the reader knows the replacement, as the writer recorded it
		Map<String, Object> changed = json(tool(reader, "what_changed",
				Map.of("project", "order-desk", "component", "hub-api", "from", "1.4.0", "from_revision", built)));
		assertThat(J.maps(changed, "replacements")).hasSize(1);

		// the directory replaced stays for whoever still serves it; HEAD.json names the new one
		Path project = data.resolve("bundles/order-desk");
		Map<String, Object> head = J.obj(Files.readAllBytes(project.resolve("HEAD.json")));
		assertThat(J.maps(head, "retired")).hasSize(1);
		assertThat(project.resolve(J.str(J.maps(head, "retired").get(0), "path"))).isDirectory();
		assertThat(project.resolve(J.str(J.map(head, "released"), "1.4.0"))).isDirectory();

		// the reader embedded queries only: every document vector is the writer's
		List<String> readerSaw = ((Fixtures.HashingEmbeddingModel) reader.getBean(EmbeddingModel.class)).seen;
		assertThat(readerSaw).isNotEmpty().allMatch((t) -> t.startsWith("Instruct: "));
		assertThat(((Fixtures.HashingEmbeddingModel) writer.getBean(EmbeddingModel.class)).seen).anyMatch((t) -> t.startsWith("Order Desk ›"));
	}

}
