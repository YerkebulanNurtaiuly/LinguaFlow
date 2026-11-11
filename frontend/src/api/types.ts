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
export type ExerciseType = NonNullable<
  LearningSettings['preferredExerciseTypes']
>[number]

// Vocabulary
export type VocabularyItem = Schemas['VocabularyItem']
export type VocabularyExample = Schemas['VocabularyExample']
export type VocabularyTag = Schemas['VocabularyTag']
export type VocabularyTagSummary = Schemas['VocabularyTagSummary']
export type TagNameRequest = Schemas['TagNameRequest']
export type VocabularyPage = Schemas['PageResponseVocabularyItem']
export type CreateVocabularyRequest = Schemas['CreateVocabularyRequest']
export type VocabularyExampleRequest = Schemas['VocabularyExampleRequest']
export type UpdateVocabularyRequest = Schemas['UpdateVocabularyRequest']
export type PartOfSpeech = NonNullable<VocabularyItem['partOfSpeech']>
export type SortOrder = Schemas['SortOrder']

// Bulk vocabulary input
export type BulkVocabularyPreviewRequest =
  Schemas['BulkVocabularyPreviewRequest']
export type BulkVocabularyFormat = NonNullable<
  BulkVocabularyPreviewRequest['format']
>
export type BulkVocabularyPreview = Schemas['BulkVocabularyPreview']
export type BulkVocabularyPreviewLine = Schemas['BulkVocabularyPreviewLine']
export type BulkVocabularyItem = Schemas['BulkVocabularyItem']
export type BulkVocabularyConfirmRequest =
  Schemas['BulkVocabularyConfirmRequest']
export type BulkVocabularyResult = Schemas['BulkVocabularyResult']
export type BulkVocabularyItemResult = Schemas['BulkVocabularyItemResult']

// Vocabulary import / export
export type VocabularyImportResult = Schemas['VocabularyImportResult']
export type VocabularyImportRow = Schemas['VocabularyImportRow']

// Grammar
export type GrammarTopic = Schemas['GrammarTopic']
export type GrammarTopicPage = Schemas['PageResponseGrammarTopic']
export type GrammarExplanation = Schemas['GrammarExplanation']
export type GrammarTopicStatusChange = Schemas['GrammarTopicStatusChange']
export type GrammarCatalogEntry = Schemas['GrammarCatalogEntry']
export type AddCatalogTopicRequest = Schemas['AddCatalogTopicRequest']
export type CreateGrammarTopicRequest = Schemas['CreateGrammarTopicRequest']
export type UpdateGrammarTopicRequest = Schemas['UpdateGrammarTopicRequest']

// Study sessions
export type StudySession = Schemas['StudySession']
export type StudySessionPage = Schemas['PageResponseStudySession']
export type StudySessionMaterial = Schemas['StudySessionMaterial']
export type StudySessionTimeline = Schemas['StudySessionTimeline']
export type StudySessionTimelineMonth = Schemas['StudySessionTimelineMonth']
export type CreateStudySessionRequest = Schemas['CreateStudySessionRequest']
export type UpdateStudySessionRequest = Schemas['UpdateStudySessionRequest']
export type AttachVocabularyRequest = Schemas['AttachVocabularyRequest']
export type AttachGrammarRequest = Schemas['AttachGrammarRequest']

// AI usage (AI-05)
export type MyAiUsage = Schemas['MyAiUsage']
export type AiUsageSummary = Schemas['AiUsageSummary']
export type AiUsageTotals = Schemas['AiUsageTotals']

// AI jobs (AI-08)
export type AiJob = Schemas['AiJob']
export type AiJobStatus = AiJob['status']
export type AiJobProgress = Schemas['AiJobProgress']

// Flashcards (FC-02)
export type Flashcard = Schemas['Flashcard']
export type FlashcardContent = Schemas['FlashcardContent']
export type FlashcardSrs = Schemas['FlashcardSrs']

// Flashcard review history (FE-FC-02)
export type PageResponseFlashcardReview = Schemas['PageResponseFlashcardReview']

// Reviews (SRS-02)
export type ReviewQueue = Schemas['ReviewQueue']
export type ReviewQueueItem = Schemas['ReviewQueueItem']
export type ReviewGradePreview = Schemas['ReviewGradePreview']
export type SubmitReviewRequest = Schemas['SubmitReviewRequest']
export type ReviewResult = Schemas['ReviewResult']
export type FlashcardReview = Schemas['FlashcardReview']
export type ReviewStats = Schemas['ReviewStats']
export type ReviewRetention = Schemas['ReviewRetention']
export type ReviewForecastDay = Schemas['ReviewForecastDay']
