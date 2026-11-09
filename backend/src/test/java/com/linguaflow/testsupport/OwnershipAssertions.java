package com.linguaflow.testsupport;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level ownership check (AUTH-05) for any endpoint that addresses a user-owned
 * resource by ID. Use it in every module's controller tests:
 * <pre>{@code
 * OwnershipAssertions.assertOnlyOwnerCanAccess(mvc, HttpMethod.GET, "/api/vocabulary/{id}",
 *         wordId, anna.accessToken(), ben.accessToken());
 * }</pre>
 * It checks that the other user gets exactly the same 404 as for an ID that does not
 * exist (so resources cannot be probed), and that the owner succeeds. The stranger's
 * request runs first, so the check also works for DELETE.
 */
public final class OwnershipAssertions {

	private OwnershipAssertions() {
	}

	public static void assertOnlyOwnerCanAccess(MockMvc mvc, HttpMethod method, String uriTemplate, UUID resourceId,
			String ownerToken, String strangerToken) throws Exception {
		assertOnlyOwnerCanAccess(mvc, method, uriTemplate, resourceId, ownerToken, strangerToken, null);
	}

	/** Variant for endpoints that need a JSON body (PATCH/PUT). */
	public static void assertOnlyOwnerCanAccess(MockMvc mvc, HttpMethod method, String uriTemplate, UUID resourceId,
			String ownerToken, String strangerToken, String jsonBody) throws Exception {
		MvcResult stranger = mvc.perform(request(method, uriTemplate, resourceId, strangerToken, jsonBody))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andReturn();
		MvcResult missing = mvc.perform(request(method, uriTemplate, UUID.randomUUID(), strangerToken, jsonBody))
			.andExpect(status().isNotFound())
			.andReturn();
		assertThat(detail(stranger)).as("another user's resource must look exactly like a missing one")
			.isEqualTo(detail(missing));

		int ownerStatus = mvc.perform(request(method, uriTemplate, resourceId, ownerToken, jsonBody))
			.andReturn()
			.getResponse()
			.getStatus();
		assertThat(ownerStatus).as("owner's %s %s", method, uriTemplate).isBetween(200, 299);
	}

	private static MockHttpServletRequestBuilder request(HttpMethod method, String uriTemplate, UUID id,
			String token, String jsonBody) {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, uriTemplate, id)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		if (jsonBody != null) {
			builder.contentType("application/json").content(jsonBody);
		}
		return builder;
	}

	private static String detail(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString().replaceAll(".*\"detail\":\"([^\"]*)\".*", "$1");
	}

}
