package com.linguaflow.testsupport;

import com.linguaflow.ai.FakeLlmClient;
import com.linguaflow.ai.LlmClient;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Wires the context's {@link FakeLlmClient} into full-context tests (QA-01): it is
 * reset before and after every test, so scripted answers, queued failures and recorded
 * calls never leak into the next test (contexts are cached and shared), and it can be
 * injected as a test method or constructor parameter:
 * <pre>{@code
 * @Test
 * void enriches(FakeLlmClient fake) {
 *     fake.respondTo(AiUseCase.VOCABULARY_ENRICHMENT, prompt -> "...");
 * }
 * }</pre>
 * {@link IntegrationTest} registers it. The test profile always uses the fake client.
 */
public class FakeLlmExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

	@Override
	public void beforeEach(ExtensionContext context) {
		fake(context).reset();
	}

	@Override
	public void afterEach(ExtensionContext context) {
		fake(context).reset();
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		return parameter.getParameter().getType() == FakeLlmClient.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		return fake(context);
	}

	/** The context's client as the fake; fails clearly when a test runs with a real provider. */
	public static FakeLlmClient fake(ExtensionContext context) {
		LlmClient client = SpringExtension.getApplicationContext(context).getBean(LlmClient.class);
		if (client instanceof FakeLlmClient fake) {
			return fake;
		}
		throw new IllegalStateException("Expected the fake LLM client in tests, found " + client.getClass().getName()
				+ "; is the test profile active?");
	}

}
