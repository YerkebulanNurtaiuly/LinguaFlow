package com.linguaflow.auth;

import java.net.URI;

import com.linguaflow.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration and login")
class AuthController {

	private final RegistrationService registrationService;

	AuthController(RegistrationService registrationService) {
		this.registrationService = registrationService;
	}

	@PostMapping("/register")
	@SecurityRequirements // public: no access token needed
	@Operation(summary = "Create an account", description = "Does not log the user in; call /api/auth/login next.")
	@ApiResponse(responseCode = "201", description = "Account created")
	@ApiResponse(responseCode = "409", description = "Email already registered (code EMAIL_TAKEN)")
	ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
		User user = registrationService.register(request);
		return ResponseEntity.created(URI.create("/api/users/me")).body(RegistrationResponse.from(user));
	}

}
