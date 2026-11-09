package com.linguaflow.testsupport;

import java.util.UUID;

import com.linguaflow.common.error.NotFoundException;
import com.linguaflow.common.persistence.OwnedEntity;
import com.linguaflow.common.persistence.OwnedRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Ownership contract every {@link OwnedRepository} must pass (AUTH-05). Extend it once per
 * owned entity, annotated with {@link OwnedRepositoryTest}:
 * <pre>{@code
 * @OwnedRepositoryTest
 * class TagRepositoryTests extends OwnedRepositoryContract<Tag> {
 *     @Autowired TagRepository tags;
 *     protected OwnedRepository<Tag> repository() { return tags; }
 *     protected Tag newEntity(UUID userId, int n) { return new Tag(userId, "de", "tag-" + n); }
 * }
 * }</pre>
 */
public abstract class OwnedRepositoryContract<T extends OwnedEntity> {

	@Autowired
	protected JdbcTemplate jdbc;

	protected UUID owner;

	protected UUID stranger;

	protected abstract OwnedRepository<T> repository();

	/** A new, valid, unsaved entity owned by {@code userId}; {@code n} makes it unique. */
	protected abstract T newEntity(UUID userId, int n);

	@BeforeEach
	void createUsers() {
		owner = TestUsers.insertLearner(jdbc);
		stranger = TestUsers.insertLearner(jdbc);
	}

	@Test
	void ownerFindsTheirEntity() {
		T saved = repository().saveAndFlush(newEntity(owner, 1));
		assertThat(repository().findByIdAndUserId(saved.getId(), owner)).isPresent();
		assertThat(repository().existsByIdAndUserId(saved.getId(), owner)).isTrue();
		assertThat(repository().requireOwned(saved.getId(), owner).getId()).isEqualTo(saved.getId());
	}

	@Test
	void otherUsersCannotFindIt() {
		T saved = repository().saveAndFlush(newEntity(owner, 1));
		assertThat(repository().findByIdAndUserId(saved.getId(), stranger)).isEmpty();
		assertThat(repository().existsByIdAndUserId(saved.getId(), stranger)).isFalse();
	}

	@Test
	void foreignAndMissingIdsFailIdentically() {
		T saved = repository().saveAndFlush(newEntity(owner, 1));
		String foreign = messageOf(() -> repository().requireOwned(saved.getId(), stranger));
		String missing = messageOf(() -> repository().requireOwned(UUID.randomUUID(), stranger));
		assertThat(foreign).isEqualTo(missing);
	}

	@Test
	void listingAndCountingOnlySeeOwnEntities() {
		repository().saveAndFlush(newEntity(owner, 1));
		repository().saveAndFlush(newEntity(owner, 2));
		repository().saveAndFlush(newEntity(stranger, 3));

		assertThat(repository().findAllByUserId(owner, PageRequest.of(0, 10)))
			.hasSize(2)
			.allSatisfy(e -> assertThat(e.getUserId()).isEqualTo(owner));
		assertThat(repository().countByUserId(owner)).isEqualTo(2);
		assertThat(repository().countByUserId(stranger)).isEqualTo(1);
	}

	@Test
	void otherUsersCannotDeleteIt() {
		T saved = repository().saveAndFlush(newEntity(owner, 1));

		assertThat(repository().deleteByIdAndUserId(saved.getId(), stranger)).isZero();
		assertThat(repository().existsByIdAndUserId(saved.getId(), owner)).isTrue();

		assertThat(repository().deleteByIdAndUserId(saved.getId(), owner)).isEqualTo(1);
		assertThat(repository().existsByIdAndUserId(saved.getId(), owner)).isFalse();
	}

	private static String messageOf(ThrowingCallable call) {
		Throwable thrown = catchThrowable(call);
		assertThat(thrown).isInstanceOf(NotFoundException.class);
		return thrown.getMessage();
	}

}
