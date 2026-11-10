package com.linguaflow.learning;

import java.util.UUID;

/** How many words or topics a study session has. */
public record SessionLinkCount(UUID sessionId, long count) {
}
