package io.github.shadowx4fox.archindex.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.shadowx4fox.archindex.bundle.Bundle;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.Tgz;
import io.github.shadowx4fox.archindex.index.Indexer;

/**
 * The publishing contract {@code ci/publish-index.sh} calls (sa-delivery:architecture-index-export):
 *
 * <pre>
 * GET  /bundles/{key}/before/{version}             200 application/gzip — the newest released bundle older than version · 404 none
 * POST /bundles/{key}/{version}?channel={channel}  body application/gzip — 201 stored · 200 a version held, the body
 *                                                  {"added":[…],"replaced":[…],"unchanged":[…]} naming the build packs and
 *                                                  {"ledger":"same|extended","ledger_added":[…]} · a ledger that only adds
 *                                                  fields to the one held (a newer plugin's) is stored ·
 *                                                  409 refused, {"reason":"chunks_differ|ledger_differs|packs_missing","packs":[…]}
 * </pre>
 *
 * plus 400 for a URL that does not match the bundle, 415 for a body that is not sent as application/gzip, 413 for a bundle over the size limit and 422 for a bundle that
 * fails its check — each with the findings as JSON. {@code GET /bundles} lists what is held and the index state.
 */
@RestController
public class BundleController {

	private final BundleStore store;

	private final Indexer indexer;

	public BundleController(BundleStore store, Indexer indexer) {
		this.store = store;
		this.indexer = indexer;
	}

	@GetMapping("/bundles/{key}/before/{version}")
	public void before(@PathVariable String key, @PathVariable String version, HttpServletResponse res) throws IOException {
		Bundle b = store.before(key, version).orElse(null);
		if (b == null) {
			res.setStatus(404);
			res.setContentType(MediaType.TEXT_PLAIN_VALUE);
			res.getWriter().write("no released bundle of " + key + " older than " + version + "\n");
			return;
		}
		res.setStatus(200);
		res.setContentType("application/gzip");
		res.setHeader("Content-Disposition", "attachment; filename=\"" + key + "-" + b.version() + ".tgz\"");
		res.setHeader("X-Architecture-Version", b.version());
		Tgz.pack(b.dir(), res.getOutputStream());
	}

	@PostMapping(path = "/bundles/{key}/{version}", consumes = { "application/gzip", "application/x-gzip", "application/octet-stream" })
	public ResponseEntity<Map<String, Object>> publish(@PathVariable String key, @PathVariable String version,
			@RequestParam(defaultValue = "released") String channel, HttpServletRequest req) throws IOException {
		BundleStore.Published p;
		try (InputStream in = req.getInputStream()) {
			p = store.publish(key, version, channel, in);
		}
		catch (BundleStore.Rejected e) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("error", e.getMessage());
			if (!e.findings().isEmpty()) {
				body.put("findings", e.findings());
			}
			return ResponseEntity.status(e.status()).body(body);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("outcome", p.outcome().name().toLowerCase());
		body.put("message", p.message());
		body.put("project", key);
		body.put("version", version);
		body.put("channel", p.bundle().channel());
		body.put("chunks", p.bundle().chunks().size());
		body.put("build_packs", List.copyOf(p.bundle().packs().keySet()));
		if (p.outcome() == BundleStore.Outcome.CONFLICT) {
			body.put("reason", p.conflict().reason());
			body.put("packs", p.conflictPacks());
			return ResponseEntity.status(409).body(body);
		}
		if (p.outcome() != BundleStore.Outcome.STORED) {
			body.put("added", p.added());
			body.put("replaced", p.replaced());
			body.put("unchanged", p.unchanged());
			body.put("ledger", p.ledgerAdded().isEmpty() ? "same" : "extended");
			body.put("ledger_added", p.ledgerAdded());
		}
		indexer.reconcile(key);
		body.put("search", p.chunksChanged() ? "indexing — GET /bundles shows when it is ready" : "unchanged");
		return ResponseEntity.status(p.outcome() == BundleStore.Outcome.STORED ? 201 : 200).body(body);
	}

	@GetMapping("/bundles")
	public Map<String, Object> list() {
		Map<String, Object> out = new LinkedHashMap<>();
		store.projects().forEach((key, p) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			p.released().forEach((v, b) -> m.put(v, slot(key, v, b)));
			p.draft().ifPresent((d) -> m.put(BundleStore.DRAFT, slot(key, BundleStore.DRAFT, d)));
			out.put(key, m);
		});
		return out;
	}

	private Map<String, Object> slot(String key, String slot, Bundle b) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("version", b.version());
		m.put("channel", b.channel());
		m.put("chunks", b.chunks().size());
		m.put("build_packs", List.copyOf(b.packs().keySet()));
		Indexer.SlotState s = indexer.state(key, slot);
		m.put("search", s == null ? "not indexed" : s);
		return m;
	}

}
