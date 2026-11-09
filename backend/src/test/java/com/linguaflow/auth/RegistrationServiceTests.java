package com.linguaflow.auth;

import com.linguaflow.common.error.ApiException;
import com.linguaflow.common.error.ErrorCode;
import com.linguaflow.user.User;
import com.linguaflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationServiceTests {

	private final UserRepository users = mock(UserRepository.class);

	private final PasswordEncoder encoder = mock(PasswordEncoder.class);

	private final RegistrationService service = new RegistrationService(users, encoder);

	private final RegistrationRequest request = new RegistrationRequest("race@example.de", "correct-horse-battery",
			"Race");

	@Test
	void concurrentDuplicateCaughtByTheUniqueIndexIsEmailTaken() {
		when(users.existsByEmail(anyString())).thenReturn(false);
		when(encoder.encode(anyString())).thenReturn("{bcrypt}hash");
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
				"duplicate key value violates unique constraint \"users_email_lower_uq\""));

		assertThatThrownBy(() -> service.register(request)).isInstanceOf(ApiException.class)
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo(ErrorCode.EMAIL_TAKEN);
	}

	@Test
	void otherIntegrityErrorsAreNotMaskedAsEmailTaken() {
		when(users.existsByEmail(anyString())).thenReturn(false);
		when(encoder.encode(anyString())).thenReturn("{bcrypt}hash");
		when(users.saveAndFlush(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("violates check constraint \"users_role_ck\""));

		assertThatThrownBy(() -> service.register(request)).isInstanceOf(DataIntegrityViolationException.class);
	}

}
