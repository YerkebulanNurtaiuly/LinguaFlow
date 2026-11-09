package com.linguaflow.auth;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserActionTokenRepository extends JpaRepository<UserActionToken, UUID> {

	/** Row lock so the same link cannot be used twice concurrently. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from UserActionToken t where t.tokenHash = :hash and t.purpose = :purpose")
	Optional<UserActionToken> findForUpdate(@Param("hash") String hash, @Param("purpose") ActionTokenPurpose purpose);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from UserActionToken t where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
	int deleteUnused(@Param("userId") UUID userId, @Param("purpose") ActionTokenPurpose purpose);

}
