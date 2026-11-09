package com.linguaflow.common.persistence;

import java.util.List;

import com.linguaflow.testsupport.OwnedRepositoryTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.QueryByExampleExecutor;
import org.springframework.data.repository.support.Repositories;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard (AUTH-05): a repository for a user-owned entity must not offer
 * unscoped CRUD methods. If this fails, make the repository extend OwnedRepository
 * instead of JpaRepository/CrudRepository.
 */
@OwnedRepositoryTest
class OwnershipGuardTests {

	private static final List<Class<?>> UNSCOPED = List.of(CrudRepository.class, ListCrudRepository.class,
			PagingAndSortingRepository.class, QueryByExampleExecutor.class);

	@Autowired
	private EntityManager em;

	@Autowired
	private ApplicationContext context;

	@Test
	void ownedEntitiesOnlyHaveOwnerScopedRepositories() {
		Repositories repositories = new Repositories(context);
		List<Class<?>> ownedEntities = em.getMetamodel()
			.getEntities()
			.stream()
			.map(EntityType::getJavaType)
			.filter(OwnedEntity.class::isAssignableFrom)
			.<Class<?>>map(type -> type)
			.toList();

		assertThat(ownedEntities).as("owned entities found").isNotEmpty();
		for (Class<?> entity : ownedEntities) {
			repositories.getRepositoryInformationFor(entity).ifPresent(info -> {
				Class<?> repository = info.getRepositoryInterface();
				assertThat(repository).as(repository.getSimpleName()).isAssignableTo(OwnedRepository.class);
				for (Class<?> unscoped : UNSCOPED) {
					assertThat(unscoped.isAssignableFrom(repository))
						.as("%s must not extend %s (unscoped access)", repository.getSimpleName(), unscoped.getSimpleName())
						.isFalse();
				}
			});
		}
	}

}
