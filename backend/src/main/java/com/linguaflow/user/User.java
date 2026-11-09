package com.linguaflow.user;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

import com.linguaflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * A registered account. The email is unique ignoring case (enforced by the
 * {@code users_email_lower_uq} index) and stored as entered, trimmed. Only the password
 * hash is kept, in Spring's {@code {id}hash} format (see PasswordEncoderConfig).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends AuditableEntity {

	public static final String DEFAULT_INTERFACE_LANGUAGE = "en";

	public static final String DEFAULT_TIMEZONE = "UTC";

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role = Role.USER;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "interface_language_code", nullable = false)
	private String interfaceLanguageCode = DEFAULT_INTERFACE_LANGUAGE;

	/** IANA zone name; defines the user's "today" for reviews and streaks. */
	@Column(nullable = false)
	private String timezone = DEFAULT_TIMEZONE;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	public User(String email, String passwordHash, String displayName) {
		changeEmail(email);
		changePasswordHash(passwordHash);
		changeDisplayName(displayName);
	}

	public void changeEmail(String email) {
		this.email = requireText(email, "email");
		this.emailVerifiedAt = null;
	}

	/** Takes an already encoded hash; never pass a raw password. */
	public void changePasswordHash(String passwordHash) {
		this.passwordHash = requireText(passwordHash, "passwordHash");
	}

	public void changeDisplayName(String displayName) {
		this.displayName = requireText(displayName, "displayName");
	}

	public void changeTimezone(ZoneId zone) {
		this.timezone = zone.getId();
	}

	public void changeInterfaceLanguage(String languageCode) {
		this.interfaceLanguageCode = requireText(languageCode, "languageCode");
	}

	public ZoneId zoneId() {
		return ZoneId.of(timezone);
	}

	public void markEmailVerified(Instant at) {
		this.emailVerifiedAt = Objects.requireNonNull(at);
	}

	public void recordLogin(Instant at) {
		this.lastLoginAt = Objects.requireNonNull(at);
	}

	public void promoteTo(Role role) {
		this.role = Objects.requireNonNull(role);
	}

	public void disable() {
		this.enabled = false;
	}

	public void enable() {
		this.enabled = true;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		String trimmed = value.strip();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return trimmed;
	}

}
