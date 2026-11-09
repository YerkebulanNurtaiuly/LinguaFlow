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
