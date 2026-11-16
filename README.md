# LinguaFlow

**AI-powered personalized language learning platform**

LinguaFlow is a language-learning platform designed to help users practice and reinforce vocabulary and grammar they have already studied.

Unlike traditional language-learning applications that rely on predefined courses, LinguaFlow allows users to manage their own learning materials. The platform uses AI to process vocabulary, generate exercises, provide explanations, and create personalized practice sessions based on the user's learning history.

The initial version focuses on **German language learning**, with the possibility of supporting additional languages in the future.

---

## Project Goals

The main goal of LinguaFlow is to create a personalized learning environment that adapts to each user's knowledge and progress.

The platform aims to:

- Allow users to add their own vocabulary and grammar topics.
- Automatically generate translations and vocabulary information using AI.
- Create flashcards for vocabulary revision.
- Generate grammar exercises based on studied topics.
- Combine new and previously studied material in practice sessions.
- Track learning progress and identify weak areas.
- Provide personalized exercises based on individual performance.

## Core Features

### 1. Vocabulary Management

- Add individual words or multiple words at once.
- Automatically generate translations and example sentences.
- Store grammatical information such as articles, plural forms, and parts of speech.
- Edit and organize vocabulary.
- Prevent duplicate vocabulary entries.

### 2. Grammar Management

- Add previously studied grammar topics.
- Store grammar descriptions and personal notes.
- Assign CEFR proficiency levels (A1–C2).
- Track grammar knowledge status.
- Distinguish between learned and mastered topics.

### 3. AI-Generated Flashcards

- Automatically create flashcards from saved vocabulary.
- Display translations and grammatical information.
- Provide example sentences.
- Track review results.

### 4. Grammar Practice

Generate exercises using different formats:

- Fill in the blanks.
- Multiple-choice questions.
- Sentence translation.
- Sentence construction.
- Error correction.
- Word ordering.
- Grammar transformations.

### 5. Mixed Practice

One of the core features of LinguaFlow.

The system combines vocabulary and grammar from different learning sessions to generate exercises based on the user's accumulated knowledge.

Three practice modes are planned:

- **Current Lesson** — practice only recently added material.
- **Mixed Practice** — combine new and previously studied material.
- **Full Revision** — practice using the entire learning history.

### 6. Learning Progress

- Track vocabulary retention.
- Monitor grammar exercise performance.
- Identify frequently repeated mistakes.
- Calculate learning statistics.
- Prioritize weak topics in future exercises.

---

## Technology Stack

| Component | Technology |
| --- | --- |
| Frontend | React 19, TypeScript 6 (strict), Vite, React Router, TanStack Query, React Hook Form, Zod |
| UI Styling | Tailwind CSS 4, a small in-house design system, light and dark themes |
| Backend | Java 21, Spring Boot 4 (modular monolith) |
| API | REST under `/api`, RFC 7807 errors, OpenAPI / Swagger, generated TypeScript types |
| Database | PostgreSQL 16, Spring Data JPA / Hibernate, Flyway migrations |
| Security | Spring Security: JWT access tokens, rotating refresh-token cookie, strict CSP |
| AI Integration | Anthropic Claude through the official Java SDK, with structured output, versioned prompts, a job queue, a shared cache and per-user quotas |
| Testing | JUnit 5, Mockito, Spring Boot Test, Testcontainers, Vitest, Testing Library, Playwright |
| Quality | Checkstyle, JaCoCo coverage gates, ESLint (strict type-checked), Prettier, Dependabot |
| Delivery | Docker images (GHCR), GitHub Actions, Render (staging and production) |
| Observability | Micrometer / Prometheus, Grafana dashboards, Sentry, structured JSON logs |

---

## Architecture

LinguaFlow follows a **Client–Server architecture** with a modular monolithic backend.

```text
                 USER
                   |
                   v
             REACT FRONTEND
                   |
                   v
                REST API
                   |
                   v
           SPRING BOOT BACKEND
                   |
       +-----------+-----------+
       |           |           |
       v           v           v
   BUSINESS       AI        LEARNING
     LOGIC    INTEGRATION    ENGINE
       |           |           |
       |           v           |
       |       AI PROVIDER     |
       |                       |
       +-----------+-----------+
                   |
                   v
               POSTGRESQL
```

### Backend Modules

- **Authentication** — registration, login, and authorization.
- **User** — profiles and language preferences.
- **Vocabulary** — vocabulary management.
- **Grammar** — grammar topic management.
- **AI** — AI provider integration and content processing.
- **Flashcard** — flashcard generation, spaced repetition, and review.
- **Exercise** — exercise generation and answer evaluation.
- **Learning** — study sessions, learning history, and practice sessions.
- **Progress** — progress aggregates, mastery rules, statistics, and weak-point analysis.
- **Common** — configuration, security, errors, persistence and other shared building blocks.

The backend remains responsible for business rules, data validation, progress calculations, and learning logic. AI is used to generate educational content and assist with open-ended answer evaluation.

The [technical specification](docs/technical-specification/README.md) describes the modules, the API, the AI integration, and the learning algorithms in detail.

---

## Learning Workflow

The main learning cycle:

```text
Add Learning Material
         |
         v
    AI Processing
         |
         v
  Generate Flashcards
         |
         v
    Practice Grammar
         |
         v
    Evaluate Answers
         |
         v
   Update Progress
         |
         v
 Personalized Practice
         |
         +------> Repeat
```

## Getting Started

With Docker installed:

```bash
docker compose up --build
```

Then open http://localhost:3000 and sign in as the demo learner `demo@linguaflow.local` /
`demo-password-123`. The [developer setup guide](docs/development/setup.md) covers running
the backend and frontend on their own, configuration, the AI in development, tests, API
changes and migrations.

## Project Structure

```text
linguaflow/
├── backend/                    Spring Boot application
│   ├── src/main/java/com/linguaflow/
│   │   ├── auth/ user/ vocabulary/ grammar/ learning/
│   │   ├── flashcard/ exercise/ progress/ ai/
│   │   └── common/
│   ├── src/main/resources/
│   │   ├── db/migration/       Flyway migrations
│   │   └── prompts/            versioned AI prompt templates
│   └── src/test/               unit, integration and AI contract tests
├── frontend/                   React application (Vite)
│   ├── src/pages/              screens
│   ├── src/components/ui/      design system
│   └── openapi.json            API snapshot the TypeScript types are generated from
├── e2e/                        Playwright end-to-end tests
├── docs/
│   ├── technical-specification/
│   ├── development/            developer setup guide
│   ├── database/               schema design
│   ├── operations/             deployment, observability, backups
│   └── security/               security review, privacy
├── ops/observability/          Prometheus and Grafana configuration
├── scripts/                    deployment and database scripts
├── docker-compose.yml          local stack
├── render.yaml                 Render Blueprint (staging and production)
├── TASKS.md                    implementation backlog
└── README.md
```

---

## Development Roadmap

Development is divided into several phases. The detailed backlog, with acceptance criteria for every task, is in [TASKS.md](TASKS.md).

### Phase 1 — Project Foundation ✅

- [x] Initialize backend and frontend projects.
- [x] Configure PostgreSQL.
- [x] Configure Flyway.
- [x] Design the database schema.
- [x] Implement user authentication.
- [x] Implement user and language management.

### Phase 2 — Learning Material Management ✅

- [x] Implement vocabulary CRUD operations.
- [x] Implement grammar CRUD operations.
- [x] Implement study sessions.
- [x] Add bulk vocabulary input.
- [x] Integrate AI vocabulary processing.
- [x] Generate flashcards.

### Phase 3 — Learning and Practice ✅

- [x] Implement flashcard interface.
- [x] Implement spaced repetition.
- [x] Generate grammar exercises.
- [x] Implement answer submission and evaluation.
- [x] Provide explanations for incorrect answers.

### Phase 4 — Personalization ✅

- [x] Implement Mixed Practice.
- [x] Analyze learning history.
- [x] Identify weak grammar topics.
- [x] Implement learning statistics.
- [x] Generate personalized exercises.

### Launch readiness ✅

- [x] Automated tests: backend unit and integration tests, AI contract tests, frontend tests, end-to-end tests.
- [x] Security review, data export, and verified account erasure.
- [x] Production images, staging and production on Render, build-once delivery.
- [x] Observability, backups, and a tested restore procedure.
- [x] Technical specification and developer guide.

### Phase 5 — Advanced Features

- [ ] Writing Practice.
- [ ] Speaking Practice.
- [ ] Audio pronunciation.
- [ ] Personalized learning plans.
- [ ] Support for additional languages.

---

## Key Design Principles

1. **User-controlled learning** — users decide what they want to study.
2. **Persistent learning history** — learning materials and results are preserved.
3. **Contextual practice** — exercises use previously studied vocabulary and grammar.
4. **Adaptive difficulty** — practice reflects the user's actual performance.
5. **AI-assisted learning** — AI generates content, while core business logic remains deterministic.
6. **Knowledge tracking** — adding a topic does not automatically mean mastering it.
7. **Extensibility** — the architecture supports additional languages and learning features.

---

## Project Status

**The MVP is feature-complete** (milestones M1–M6 in [TASKS.md](TASKS.md)). A learner can:

- register, choose German, and set daily goals;
- add words one by one, in bulk, or from CSV, and have the AI fill in translations, grammar, and examples;
- collect grammar topics from a catalog of 52 or add their own, with AI explanations on request;
- group material into lessons;
- review flashcards with spaced repetition;
- practise AI-written exercises in seven formats, graded right away, with explanations of mistakes;
- run mixed practice sessions chosen from what is new, due, or weak;
- follow their progress: statistics, a heatmap, mastery, weak points, and frequent mistakes.

Every change runs through CI (about 1,050 backend tests with coverage gates, about 290 frontend tests, and an end-to-end learner journey). Each green commit on `main` is built once into Docker images and, once the Render deploy hooks are configured, deployed to staging automatically; production is promoted by hand.

**Before a public launch**, a few operational steps remain ([deployment guide](docs/operations/deployment.md#known-gaps-before-launch)):

- an email provider for verification and password-reset emails;
- the operator's name and contact details on the privacy page;
- a Sentry DSN, a metrics scraper, and uptime monitoring for the deployed environments;
- one restore drill on Render staging.

**Next:** Phase 5, the advanced features (`ADV-*` in [TASKS.md](TASKS.md)).

To try it locally, see [Getting Started](#getting-started).

---

## Future Vision

LinguaFlow aims to become a personalized language-learning environment that connects vocabulary acquisition, grammar practice, spaced repetition, and AI-assisted learning in one platform.

The long-term goal is to help learners continuously reinforce what they have already studied instead of simply progressing through predefined lessons.
