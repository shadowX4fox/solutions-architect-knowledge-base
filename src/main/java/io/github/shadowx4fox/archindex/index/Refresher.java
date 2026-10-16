package io.github.shadowx4fox.archindex.index;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.shadowx4fox.archindex.bundle.BundleStore;

/**
 * A reader instance following the writer: every {@code interval} it reloads the projects a publish changed and asks
 * the index again about the slots not ready yet — the writer marks a slot once it has indexed it.
 */
public class Refresher implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(Refresher.class);

	private final BundleStore store;

	private final Indexer indexer;

	private final Duration interval;

	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor((r) -> {
		Thread t = new Thread(r, "archindex-refresher");
		t.setDaemon(true);
		return t;
	});

	public Refresher(BundleStore store, Indexer indexer, Duration interval) {
		this.store = store;
		this.indexer = indexer;
		this.interval = interval;
	}

	public void start() {
		long ms = Math.max(100, interval.toMillis());
		timer.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
		log.info("reader: following the writer every {} ms", ms);
	}

	/** One round: what a publish changed, and what is still waiting on the writer's index. */
	public void tick() {
		try {
			Set<String> again = new TreeSet<>(store.refresh());
			store.projects().keySet().stream().filter((k) -> !indexer.settled(k)).forEach(again::add);
			again.forEach(indexer::reconcile);
		}
		catch (RuntimeException e) {
			log.warn("refresh failed: {}", e.getMessage());
		}
	}

	@Override
	public void close() {
		timer.shutdownNow();
	}

}
