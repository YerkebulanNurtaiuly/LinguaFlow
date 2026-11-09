package com.linguaflow.common.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.linguaflow.LinguaFlowApplication;
import com.linguaflow.TestcontainersConfiguration;
import com.linguaflow.common.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import testsupport.auditing.AuditedTestEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, JpaAuditingConfig.class })
@ActiveProfiles("test")
class AuditableEntityTests {

	private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

	@Autowired
	private TestEntityManager em;

	@Autowired
	private MutableClock clock;

	@Test
	void setsBothTimestampsOnInsertAndOnlyUpdatedAtOnUpdate() {
		clock.set(T0);
		AuditedTestEntity entity = em.persistFlushFind(new AuditedTestEntity("Hund"));

		assertThat(entity.getCreatedAt()).isEqualTo(T0);
		assertThat(entity.getUpdatedAt()).isEqualTo(T0);

		clock.set(T0.plus(Duration.ofMinutes(5)));
		entity.rename("Katze");
		em.flush();

		assertThat(entity.getCreatedAt()).isEqualTo(T0);
		assertThat(entity.getUpdatedAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
	}

	@TestConfiguration(proxyBeanMethods = false)
	// App entities too: repositories in com.linguaflow need their entities to be managed
	@EntityScan(basePackageClasses = { AuditedTestEntity.class, LinguaFlowApplication.class })
	static class Config {

		@Bean
		MutableClock clock() {
			return new MutableClock();
		}

	}

	static class MutableClock extends Clock {

		private Instant now = Instant.EPOCH;

		void set(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
