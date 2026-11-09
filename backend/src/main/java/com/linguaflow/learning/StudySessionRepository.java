package com.linguaflow.learning;

import com.linguaflow.common.persistence.OwnedRepository;

/** Scoped by owner (AUTH-05); session queries are added in SES-01. */
public interface StudySessionRepository extends OwnedRepository<StudySession> {

}
