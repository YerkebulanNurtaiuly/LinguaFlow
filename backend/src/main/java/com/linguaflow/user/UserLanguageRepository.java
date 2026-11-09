package com.linguaflow.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The key contains the user ID, so every lookup is owner-scoped by construction. Like
 * OwnedRepository, it exposes no unscoped findAll/deleteById.
 */
public interface UserLanguageRepository extends Repository<UserLanguage, UserLanguage.Key> {

	UserLanguage save(UserLanguage userLanguage);

	Optional<UserLanguage> findById(UserLanguage.Key id);

	boolean existsById(UserLanguage.Key id);

	@Query("select l from UserLanguage l where l.id.userId = :userId order by l.createdAt")
	List<UserLanguage> findAllForUser(@Param("userId") UUID userId);

	@Query("select l from UserLanguage l where l.id.userId = :userId and l.active = true")
	Optional<UserLanguage> findActive(@Param("userId") UUID userId);

	void delete(UserLanguage userLanguage);

	/** Step one of switching: must run (and flush) before the new language is activated. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update UserLanguage l set l.active = false where l.id.userId = :userId and l.active = true")
	int deactivateAll(@Param("userId") UUID userId);

}
