package com.linguaflow.common.security;

import java.util.UUID;

import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.auth.AccessTokenService;
import com.linguaflow.auth.RegistrationService;
import com.linguaflow.testsupport.OwnershipAssertions;
import com.linguaflow.testsupport.TestUsers;
import com.linguaflow.testsupport.TestUsers.TestUser;
import com.linguaflow.user.UserRepository;
import com.linguaflow.vocabulary.Tag;
import com.linguaflow.vocabulary.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the AUTH-05 pattern end to end (token -> @CurrentUserId -> OwnedRepository ->
 * 404) and the reusable OwnershipAssertions helper, using a test controller until the
 * real module controllers exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, OwnershipIsolationTests.TagTestController.class })
@ActiveProfiles("test")
class OwnershipIsolationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TagRepository tags;

	@Autowired
	private UserRepository users;

	@Autowired
	private RegistrationService registration;

	@Autowired
	private AccessTokenService accessTokens;

	@Autowired
	private JdbcTemplate jdbc;

	private TestUser anna;

	private TestUser ben;

	@BeforeEach
	void setUp() {
		users.deleteAll();
		TestUsers testUsers = new TestUsers(registration, accessTokens, jdbc);
		anna = testUsers.learner("anna@example.de");
		ben = testUsers.learner("ben@example.de");
	}

	@Test
	void onlyTheOwnerCanReadTheirResource() throws Exception {
		UUID annasTag = tags.save(new Tag(anna.id(), "de", "Essen")).getId();
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/test/tags/{id}", annasTag,
				anna.accessToken(), ben.accessToken());
	}

	@Test
	void onlyTheOwnerCanDeleteTheirResource() throws Exception {
		UUID annasTag = tags.save(new Tag(anna.id(), "de", "Tiere")).getId();
		OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.DELETE, "/api/test/tags/{id}", annasTag,
				anna.accessToken(), ben.accessToken());
		assertThat(tags.existsByIdAndUserId(annasTag, anna.id())).isFalse();
	}

	@RestController
	static class TagTestController {

		private final TagRepository tags;

		TagTestController(TagRepository tags) {
			this.tags = tags;
		}

		@GetMapping("/api/test/tags/{id}")
		String get(@CurrentUserId UUID userId, @PathVariable UUID id) {
			return tags.requireOwned(id, userId).getName();
		}

		@DeleteMapping("/api/test/tags/{id}")
		ResponseEntity<Void> delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
			tags.requireOwned(id, userId);
			tags.deleteByIdAndUserId(id, userId);
			return ResponseEntity.noContent().build();
		}

	}

}
