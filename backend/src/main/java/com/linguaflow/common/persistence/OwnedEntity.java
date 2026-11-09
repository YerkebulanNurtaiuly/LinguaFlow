package com.linguaflow.common.persistence;

import java.util.UUID;

/**
 * An entity that belongs to one user. Its repository must extend {@link OwnedRepository}
 * so every lookup is scoped by the owner (AUTH-05); OwnershipGuardTests enforces this.
 */
public interface OwnedEntity {

	UUID getId();

	UUID getUserId();

}
