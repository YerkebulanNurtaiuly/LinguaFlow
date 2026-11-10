package com.linguaflow.vocabulary;

import java.util.UUID;

/** Number of words carrying a tag. */
public record TagUsage(UUID tagId, long wordCount) {
}
