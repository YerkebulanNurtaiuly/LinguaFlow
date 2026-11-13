package com.linguaflow.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linguaflow.TestcontainersConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The standard full-context test (QA-01): the whole app with MockMvc, the test profile,
 * PostgreSQL from the shared Testcontainers instance (a database per context), and the
 * {@link FakeLlmExtension}. Combine it with {@link TestUsers} for learners and tokens and
 * {@link TestData} for material.
 * <p>
 * Tests with the same annotations share one cached context, so a test that adds beans
 * ({@code @MockitoBean}, its own {@code @TestConfiguration}) starts a context of its own:
 * keep those for when they're needed.
 */
@Documented
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(FakeLlmExtension.class)
public @interface IntegrationTest {

}
