package io.github.shadowx4fox.archindex.bundle;

import java.util.List;
import java.util.Map;

/** One line of chunks.jsonl: the unit a search hit returns. */
public record Chunk(String id, String contentHash, String text, Map<String, Object> meta) {

	public String kind() {
		return J.str(meta, "kind");
	}

	public String file() {
		return J.str(meta, "file");
	}

	public String title() {
		return J.str(meta, "title");
	}

	public List<String> components() {
		return J.strings(meta, "components");
	}

	public List<String> adrs() {
		return J.strings(meta, "adrs");
	}

	public List<Object> lines() {
		return J.list(meta, "lines");
	}

}
