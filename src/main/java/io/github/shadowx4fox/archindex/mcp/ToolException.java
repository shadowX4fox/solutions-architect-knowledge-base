package io.github.shadowx4fox.archindex.mcp;

import java.io.Serial;

/** A question the server cannot answer as asked — returned to the agent as a tool error that says what to ask instead. */
public class ToolException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public ToolException(String message) {
		super(message);
	}

}
