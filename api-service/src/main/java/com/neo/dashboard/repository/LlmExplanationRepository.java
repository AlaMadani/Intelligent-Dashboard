package com.neo.dashboard.repository;

import com.neo.dashboard.entity.LlmExplanation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link LlmExplanation} entities.
 * Explanations are cached LLM-generated natural-language descriptions of anomaly
 * events, keyed by event, language, style, and evidence content hash.
 */
@Repository
public interface LlmExplanationRepository extends JpaRepository<LlmExplanation, Long> {

    /**
     * Returns the current (active) explanation for an event that exactly matches
     * the given language, style, recommended-actions flag, and evidence hash.
     * Used to serve a cache hit when identical parameters are requested again.
     */
    Optional<LlmExplanation> findTopByEventIdAndLanguageAndStyleAndIncludeRecommendedActionsAndEvidenceHashAndCurrentTrue(
            String eventId, String language, String style, boolean includeRecommendedActions, String evidenceHash);

    /** Returns the most recently updated current explanation for the given event. */
    Optional<LlmExplanation> findTopByEventIdAndCurrentTrueOrderByUpdatedAtDesc(String eventId);

    /**
     * Bulk-flags all existing current explanations for the given event/params
     * combination as not-current, so a newly generated explanation becomes the
     * active one. Runs inside a transaction.
     *
     * @return the number of rows updated
     */
    @Modifying
    @Transactional
    @Query("UPDATE LlmExplanation e SET e.current = false WHERE e.eventId = :eventId AND e.language = :language AND e.style = :style AND e.includeRecommendedActions = :includeActions AND e.current = true")
    int markPreviousAsNotCurrent(@Param("eventId") String eventId,
                                 @Param("language") String language,
                                 @Param("style") String style,
                                 @Param("includeActions") boolean includeRecommendedActions);
}
