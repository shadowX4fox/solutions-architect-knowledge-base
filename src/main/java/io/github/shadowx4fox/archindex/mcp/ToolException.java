package io.github.shadowx4fox.archindex.mcp;

/** A question the server cannot answer as asked — returned to the agent as a tool error that says what to ask instead. */
public class ToolException extends RuntimeException {

	public ToolException(String message) {
		super(message);
	}

}
