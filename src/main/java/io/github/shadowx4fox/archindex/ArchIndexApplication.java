package io.github.shadowx4fox.archindex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import io.github.shadowx4fox.archindex.build.BuildPackService;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.changes.ChangesService;
import io.github.shadowx4fox.archindex.index.Embedder;
import io.github.shadowx4fox.archindex.index.FileVectorIndex;
import io.github.shadowx4fox.archindex.index.Indexer;
import io.github.shadowx4fox.archindex.index.MemoryVectorIndex;
import io.github.shadowx4fox.archindex.index.QdrantVectorIndex;
import io.github.shadowx4fox.archindex.index.Refresher;
import io.github.shadowx4fox.archindex.index.SearchService;
import io.github.shadowx4fox.archindex.index.SparseEncoder;
import io.github.shadowx4fox.archindex.index.VectorIndex;
import io.github.shadowx4fox.archindex.ledger.DocumentService;
import io.github.shadowx4fox.archindex.ledger.LedgerService;
import io.github.shadowx4fox.archindex.mcp.ArchitectureTools;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.web.TokenFilter;

@SpringBootApplication
@EnableConfigurationProperties(ArchIndexProperties.class)
public class ArchIndexApplication {

	private static final Logger log = LoggerFactory.getLogger(ArchIndexApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(ArchIndexApplication.class, args);
	}

	@Bean
	BundleStore bundleStore(ArchIndexProperties p) {
		return new BundleStore(p.dataDir(), p.maxBundleBytes(), p.role() == ArchIndexProperties.Role.READER,
				p.retainReplaced());
	}

	@Bean
	VectorIndex vectorIndex(ArchIndexProperties p) {
		return switch (p.store()) {
			case QDRANT -> new QdrantVectorIndex(p.qdrant(), p.embedding().dimensions());
			case FILE -> new FileVectorIndex(p.file().path() != null ? p.file().path()
					: p.dataDir().resolve("index").resolve("vectors-" + p.embedding().dimensions() + ".bin"),
					p.embedding().dimensions());
			case MEMORY -> new MemoryVectorIndex();
		};
	}

	@Bean
	SparseEncoder sparseEncoder() {
		return new SparseEncoder();
	}

	@Bean
	Embedder embedder(EmbeddingModel model, ArchIndexProperties p,
			@Value("${spring.ai.ollama.embedding.options.model:qwen3-embedding:0.6b}") String modelName) {
		return new Embedder(model, p.embedding(), modelName);
	}

	@Bean
	Indexer indexer(BundleStore store, VectorIndex index, Embedder embedder, SparseEncoder sparse, ArchIndexProperties p) {
		if (p.role() == ArchIndexProperties.Role.READER && !index.shared()) {
			throw new IllegalStateException("archindex.role=reader reads the index the writer fills: it needs archindex.store=qdrant, not "
					+ index.describe());
		}
		return new Indexer(store, index, embedder, sparse, p.role() == ArchIndexProperties.Role.READER);
	}

	@Bean
	Refresher refresher(BundleStore store, Indexer indexer, ArchIndexProperties p) {
		return new Refresher(store, indexer, p.refreshInterval());
	}

	@Bean
	Bundles bundles(BundleStore store) {
		return new Bundles(store);
	}

	@Bean
	LedgerService ledgerService(Bundles bundles, Indexer indexer) {
		return new LedgerService(bundles, indexer);
	}

	@Bean
	SearchService searchService(Bundles bundles, VectorIndex index, Indexer indexer, Embedder embedder,
			SparseEncoder sparse, ArchIndexProperties p) {
		return new SearchService(bundles, index, indexer, embedder, sparse, p.search());
	}

	@Bean
	BuildPackService buildPackService(Bundles bundles, ArchIndexProperties p) {
		if (p.publicUrl() == null || p.publicUrl().isBlank()) {
			log.info("archindex.public-url is not set: build pack parts list no download_url");
		}
		return new BuildPackService(bundles, p.search().maxPartChars(), p.publicUrl());
	}

	@Bean
	ChangesService changesService(Bundles bundles) {
		return new ChangesService(bundles);
	}

	@Bean
	DocumentService documentService(Bundles bundles, ArchIndexProperties p) {
		return new DocumentService(bundles, p.search().maxPartChars());
	}

	@Bean
	ArchitectureTools architectureTools(LedgerService ledger, SearchService search, BuildPackService packs,
			ChangesService changes, DocumentService documents) {
		return new ArchitectureTools(ledger, search, packs, changes, documents);
	}

	@Bean
	FilterRegistrationBean<TokenFilter> tokenFilter(ArchIndexProperties p,
			@Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpPath) {
		if (p.ingestToken() == null || p.ingestToken().isBlank()) {
			log.warn("archindex.ingest-token is not set: anyone who reaches /bundles can publish");
		}
		if (p.mcpToken() == null || p.mcpToken().isBlank()) {
			log.warn("archindex.mcp-token is not set: the MCP endpoint {} and /packs are open", mcpPath);
		}
		FilterRegistrationBean<TokenFilter> r = new FilterRegistrationBean<>(new TokenFilter(p.ingestToken(), p.mcpToken(), mcpPath));
		r.setOrder(0);
		return r;
	}

	@EventListener(ApplicationReadyEvent.class)
	void indexOnStart(ApplicationReadyEvent e) {
		e.getApplicationContext().getBean(Indexer.class).reconcileAll();
		if (e.getApplicationContext().getBean(BundleStore.class).readOnly()) {
			e.getApplicationContext().getBean(Refresher.class).start();
		}
	}

}
