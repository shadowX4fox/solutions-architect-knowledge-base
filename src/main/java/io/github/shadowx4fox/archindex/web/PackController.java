package io.github.shadowx4fox.archindex.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.shadowx4fox.archindex.build.BuildPackService;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/**
 * A build pack part's raw bytes, for the agent to save with curl rather than re-type from a tool result — the
 * {@code download_url} that {@code get_build_pack} lists. Read access: it takes the MCP token, never the pipeline's.
 *
 * <pre>
 * GET /packs/{key}/{version}/{slug}/{path}[?pack_revision=…]   {version}: a released version, latest or draft
 *   200 the file as stored · ETag "&lt;sha256&gt;" · X-Pack-Revision · Cache-Control no-cache (a pack can be replaced within its version)
 *   304 If-None-Match matched · 404 no such project, version, pack or part · 409 pack_revision is not the current one
 * </pre>
 */
@RestController
public class PackController {

	private final BuildPackService packs;

	public PackController(BuildPackService packs) {
		this.packs = packs;
	}

	@GetMapping("/packs/{key}/{version}/{slug}/{*path}")
	public ResponseEntity<?> part(@PathVariable String key, @PathVariable String version, @PathVariable String slug,
			@PathVariable String path, @RequestParam(name = "pack_revision", required = false) String revision,
			@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		BuildPackService.Download d;
		try {
			d = packs.download(key, version, slug, path.startsWith("/") ? path.substring(1) : path, revision);
		}
		catch (ToolException e) {
			return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(e.getMessage() + "\n");
		}
		catch (BuildPackService.RevisionMoved e) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("error", e.getMessage());
			body.put("pack_revision", e.current());
			body.put("next", "get_build_pack again, then what_changed with from_revision");
			return ResponseEntity.status(409).header("X-Pack-Revision", e.current()).body(body);
		}
		String etag = "\"" + d.sha256() + "\"";
		ResponseEntity.BodyBuilder r = ResponseEntity.ok()
			.eTag(etag)
			.header("X-Pack-Revision", d.packRevision())
			.header("X-Architecture-Version", d.draft() ? "draft" : d.version())
			.cacheControl(CacheControl.noCache());
		if (ifNoneMatch != null && (ifNoneMatch.contains(etag) || ifNoneMatch.strip().equals("*"))) {
			return ResponseEntity.status(304).headers(r.build().getHeaders()).build();
		}
		return r.contentType(type(d.path())).contentLength(d.bytes().length).body(d.bytes());
	}

	/** By extension; text as UTF-8, which is what the export writes. */
	static MediaType type(String path) {
		String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		return switch (ext) {
			case "yaml", "yml" -> MediaType.parseMediaType("application/yaml");
			case "json", "avsc" -> MediaType.APPLICATION_JSON;
			case "md" -> MediaType.parseMediaType("text/markdown;charset=UTF-8");
			case "sql", "proto", "graphql", "graphqls", "txt" -> MediaType.parseMediaType("text/plain;charset=UTF-8");
			default -> MediaType.APPLICATION_OCTET_STREAM;
		};
	}

}
