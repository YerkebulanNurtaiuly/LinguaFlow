package com.linguaflow.auth.password;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Password policy (NIST SP 800-63B style: length over composition rules):
 * at least {@value PasswordPolicy#MIN_LENGTH} characters, at most
 * {@value PasswordPolicy#MAX_BYTES} UTF-8 bytes (BCrypt ignores anything longer), not
 * only whitespace, and not one of the most common passwords.
 */
@Documented
@Constraint(validatedBy = PasswordPolicy.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

	String message() default "does not meet the password policy";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
