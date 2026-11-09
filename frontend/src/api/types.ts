/**
 * Friendly aliases for the generated OpenAPI types (src/api/schema.ts).
 * Import API DTO types from here; never hand-write a type the backend already defines.
 *
 * Regenerate after backend API changes:
 *   backend:  ./mvnw test -Dtest=OpenApiDocsTests -Dopenapi.update=true
 *   frontend: npm run api:generate
 */
import type { components } from './schema'

export type Schemas = components['schemas']

/** Error body of every failed API call (RFC 7807 problem details). */
export type ApiProblem = Schemas['ApiProblem']
export type ApiErrorCode = ApiProblem['code']
export type ApiFieldError = Schemas['FieldError']

// Auth
export type RegistrationRequest = Schemas['RegistrationRequest']
export type RegistrationResponse = Schemas['RegistrationResponse']
export type LoginRequest = Schemas['LoginRequest']
export type TokenResponse = Schemas['TokenResponse']
export type VerifyEmailRequest = Schemas['VerifyEmailRequest']
export type PasswordResetRequest = Schemas['PasswordResetRequest']
export type ResetPasswordRequest = Schemas['ResetPasswordRequest']

// Profile
export type UserProfile = Schemas['UserProfileResponse']
export type UpdateProfileRequest = Schemas['UpdateProfileRequest']
export type ChangePasswordRequest = Schemas['ChangePasswordRequest']
export type DeleteAccountRequest = Schemas['DeleteAccountRequest']

// Languages
export type Language = Schemas['LanguageResponse']
export type UserLanguage = Schemas['UserLanguageResponse']
export type AddLanguageRequest = Schemas['AddLanguageRequest']
export type UpdateLanguageRequest = Schemas['UpdateLanguageRequest']
export type CefrLevel = NonNullable<UserLanguage['selfAssessedLevel']>
export type LearningSettings = Schemas['LearningSettings']
export type LearningSettingsUpdate = Schemas['SettingsUpdate']
export type ExerciseType = LearningSettings['preferredExerciseTypes'][number]
