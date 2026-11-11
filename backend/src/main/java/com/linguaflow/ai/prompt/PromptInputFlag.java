package com.linguaflow.ai.prompt;

/**
 * What prompt safety (AI-07) changed or noticed in a rendering's input. Logged by
 * template, never with the text itself.
 */
public enum PromptInputFlag {

	/** A value was cut to its variable's limit, or a list to its item limit. */
	TRUNCATED,
	/** Control, invisible or bidirectional-override characters were removed. */
	CONTROL_CHARACTERS_REMOVED,
	/** {@code <} or {@code >} were escaped, so input can't close a delimiting tag. */
	MARKUP_ESCAPED,
	/** An email address, phone number, IBAN or long number was replaced before sending. */
	PERSONAL_DATA_REDACTED,
	/**
	 * The input reads like an attempt to instruct the model ("ignore previous
	 * instructions"). It's still sent, delimited as data, but its result isn't shared
	 * through the AI cache.
	 */
	INJECTION_SUSPECTED

}
