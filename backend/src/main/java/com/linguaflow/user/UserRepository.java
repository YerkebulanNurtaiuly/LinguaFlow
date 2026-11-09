package com.linguaflow.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Email lookups use {@code lower(email)} explicitly so they hit the
 * {@code users_email_lower_uq} expression index. (Spring Data's derived
 * {@code ...IgnoreCase} methods compare with {@code upper()}, which would not.)
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	@Query("select u from User u where lower(u.email) = lower(:email)")
	Optional<User> findByEmail(@Param("email") String email);

	@Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
	boolean existsByEmail(@Param("email") String email);

}
