package io.github.shadowx4fox.archindex.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer tokens until the organization's identity provider is wired (an open decision): {@code archindex.ingest-token} guards
 * {@code /bundles/**} — the pipeline secret {@code SA_INDEX_TOKEN} — and {@code archindex.mcp-token}, when set, guards
 * what the IDE reads: the MCP endpoint and the raw pack parts under {@code /packs/**}. An unset token leaves its path
 * open; the server logs that at start.
 */
public class TokenFilter extends OncePerRequestFilter {

	private final String ingestToken;

	private final String mcpToken;

	private final String mcpPath;

	public TokenFilter(String ingestToken, String mcpToken, String mcpPath) {
		this.ingestToken = blank(ingestToken) ? null : ingestToken;
		this.mcpToken = blank(mcpToken) ? null : mcpToken;
		this.mcpPath = mcpPath;
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		// the decoded, normalized path the dispatcher routes on — never the raw URI, where /%62undles still reaches /bundles
		String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
		String want = path.startsWith("/bundles") ? ingestToken
				: path.startsWith(mcpPath) || path.startsWith("/packs") ? mcpToken : null;
		if (want != null && !matches(req.getHeader("Authorization"), want)) {
			res.setStatus(401);
			res.setHeader("WWW-Authenticate", "Bearer");
			res.setContentType("text/plain");
			res.getWriter().write("a bearer token is required\n");
			return;
		}
		chain.doFilter(req, res);
	}

	private static boolean matches(String header, String token) {
		if (header == null || !header.startsWith("Bearer ")) {
			return false;
		}
		return MessageDigest.isEqual(header.substring(7).trim().getBytes(StandardCharsets.UTF_8),
				token.getBytes(StandardCharsets.UTF_8));
	}

}
