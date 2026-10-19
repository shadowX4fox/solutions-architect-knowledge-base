package io.github.shadowx4fox.archindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.shadowx4fox.archindex.ArchIndexProperties.Escalation;
import io.github.shadowx4fox.archindex.bundle.BundleStore;
import io.github.shadowx4fox.archindex.bundle.J;
import io.github.shadowx4fox.archindex.escalation.Tier0;
import io.github.shadowx4fox.archindex.escalation.Tier0Service;
import io.github.shadowx4fox.archindex.mcp.Bundles;
import io.github.shadowx4fox.archindex.mcp.ToolException;

/** The Tier 0 gates that depend on the release's support block. */
class Tier0Test {

	@TempDir
	Path tmp;

	Tier0Service service(boolean tier0) throws Exception {
		Path b = Fixtures.copy(Fixtures.bundle("1.4.0"), tmp.resolve("b"));
		Map<String, Object> m = J.obj(Files.readAllBytes(b.resolve("manifest.json")));
		J.map(m, "support").put("tier0", tier0);
		Files.writeString(b.resolve("manifest.json"), J.write(m));
		BundleStore store = new BundleStore(tmp.resolve("data"), 50_000_000);
		store.publish("order-desk", "1.4.0", "released", new ByteArrayInputStream(Fixtures.tgz(b)));
		return new Tier0Service(new Bundles(store), new Escalation(List.of(EscalationTest.GITHUB)));
	}

	Tier0.Request doc() {
		return new Tier0.Request("doc-fix", "Fix a line", "fix the doc", List.of(), "Desk team", null,
				Map.of("file", "docs/01.md", "line", 3, "before", "a", "after", "b"));
	}

	@Test
	void withoutTier0InForceNothingIsPreviewed() throws Exception {
		assertThatThrownBy(() -> service(false).preview("order-desk", doc())).isInstanceOf(ToolException.class)
			.hasMessageContaining("Tier 0 starts at the first release");
		assertThat(service(true).preview("order-desk", doc())).containsKey("preview_id");
	}

}
