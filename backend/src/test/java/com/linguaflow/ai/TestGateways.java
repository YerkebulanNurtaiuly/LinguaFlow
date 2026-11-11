package com.linguaflow.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linguaflow.ai.usage.AiQuota;
import com.linguaflow.ai.usage.AiRequestLog;
import com.linguaflow.common.config.LinguaFlowProperties;

/** Gateways for unit tests: no quota, no usage log, the default circuit breaker. */
public final class TestGateways {

	private TestGateways() {
	}

	public static AiGateway unlimited(LlmClient client) {
		LinguaFlowProperties properties = mock(LinguaFlowProperties.class);
		when(properties.ai())
			.thenReturn(new LinguaFlowProperties.Ai(null, null, null, null, null, null, null, null, null));
		return new AiGateway(client, mock(AiQuota.class), mock(AiRequestLog.class), properties);
	}

}
