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

| Component            | Technology                                         |
| -------------------- | -------------------------------------------------- |
| Frontend             | React                                              |
| Programming Language | TypeScript                                         |
| UI Styling           | Tailwind CSS                                       |
| Backend              | Java                                               |
| Backend Framework    | Spring Boot                                        |
| API                  | REST API                                           |
| Database             | PostgreSQL                                         |
| ORM                  | Spring Data JPA / Hibernate                        |
| Database Migrations  | Flyway                                             |
| Security             | Spring Security                                    |
| AI Integration       | LLM API                                            |
| API Documentation    | OpenAPI / Swagger                                  |
| Build Tool           | Maven                                              |
| Containerization     | Docker                                             |
| Testing              | JUnit 5, Mockito, Spring Boot Test, Testcontainers |

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
- **Flashcard** — flashcard generation and review.
- **Exercise** — exercise generation and answer evaluation.
- **Learning** — study sessions and learning history.
- **Progress** — statistics, spaced repetition, and weak-point analysis.

The backend remains responsible for business rules, data validation, progress calculations, and learning logic. AI is used to generate educational content and assist with open-ended answer evaluation.

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

## Project Structure

The planned repository structure:

```text
LinguaFlow/
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   │       └── db/
│   │   │           └── migration/
│   │   └── test/
│   └── pom.xml
│
├── frontend/
│   ├── src/
│   ├── public/
│   └── package.json
│
├── docs/
│   ├── database/
│   └── technical-specification/
│
└── README.md
```

*This structure represents the planned project organization and may evolve during development.*

---

## Development Roadmap

Development is divided into several phases.

### Phase 1 — Project Foundation

- [ ] Initialize backend and frontend projects.
- [ ] Configure PostgreSQL.
- [ ] Configure Flyway.
- [ ] Design the database schema.
- [ ] Implement user authentication.
- [ ] Implement user and language management.

### Phase 2 — Learning Material Management

- [ ] Implement vocabulary CRUD operations.
- [ ] Implement grammar CRUD operations.
- [ ] Implement study sessions.
- [ ] Add bulk vocabulary input.
- [ ] Integrate AI vocabulary processing.
- [ ] Generate flashcards.

### Phase 3 — Learning and Practice

- [ ] Implement flashcard interface.
- [ ] Implement spaced repetition.
- [ ] Generate grammar exercises.
- [ ] Implement answer submission and evaluation.
- [ ] Provide explanations for incorrect answers.

### Phase 4 — Personalization

- [ ] Implement Mixed Practice.
- [ ] Analyze learning history.
- [ ] Identify weak grammar topics.
- [ ] Implement learning statistics.
- [ ] Generate personalized exercises.

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

\<PRIVATE_STATUS_PLACEHOLDER>

LinguaFlow is under active development. The current priority is establishing the database foundation and implementing the core MVP functionality.

Features described in this README represent the project vision and development plan; not all features are implemented yet.

---

## Future Vision

LinguaFlow aims to become a personalized language-learning environment that connects vocabulary acquisition, grammar practice, spaced repetition, and AI-assisted learning in one platform.

The long-term goal is to help learners continuously reinforce what they have already studied instead of simply progressing through predefined lessons.
