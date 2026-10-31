package io.github.shadowx4fox.archindex.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.ObjectProvider;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Icon;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;

/**
 * The MCP server, built here rather than by Spring AI's {@code McpServerStatelessAutoConfiguration} (excluded in
 * application.yaml): that one gives {@code serverInfo} a name and a version only, and this one adds Alia's title, the
 * repository and her escudo, so a client that shows server icons shows hers. Tools only, as this server has nothing else.
 */
public final class AliaServer {

	public static final String TITLE = "Alia — the architecture MCP server";

	public static final String WEBSITE = "https://github.com/shadowX4fox/solutions-architect-knowledge-base";

	private AliaServer() {
	}

	public static McpStatelessSyncServer build(McpStatelessServerTransport transport, McpServerProperties p, String icon,
			ObjectProvider<List<SyncToolSpecification>> tools) {
		Implementation info = Implementation.builder(p.getName(), p.getVersion())
			.title(TITLE)
			.websiteUrl(WEBSITE)
			.icons(icons(icon))
			.build();
		return McpServer.sync(transport)
			.serverInfo(info)
			.capabilities(ServerCapabilities.builder().tools(false).build())
			.tools(tools.stream().flatMap(List::stream).toList())
			.instructions(p.getInstructions())
			.requestTimeout(p.getRequestTimeout())
			.immediateExecution(true)
			.build();
	}

	/** Unset: the escudo, as a data: URI, so no client reaches outside the network for it. {@code none}: no icon. */
	static List<Icon> icons(String icon) {
		if (icon == null || icon.isBlank()) {
			return List.of(Icon.builder("data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(escudo()))
				.mimeType("image/svg+xml")
				.sizes(List.of("any"))
				.build());
		}
		if (icon.equals("none")) {
			return null;
		}
		return List.of(Icon.builder(icon).build());
	}

	private static byte[] escudo() {
		try (InputStream in = AliaServer.class.getResourceAsStream("/alia/escudo.svg")) {
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
