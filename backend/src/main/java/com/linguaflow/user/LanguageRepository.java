package com.linguaflow.user;

import org.springframework.data.jpa.repository.JpaRepository;

/** Global reference data, not user-owned. */
public interface LanguageRepository extends JpaRepository<Language, String> {

}
