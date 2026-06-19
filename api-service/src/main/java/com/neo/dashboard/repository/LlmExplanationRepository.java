package com.neo.dashboard.repository;

import com.neo.dashboard.entity.LlmExplanation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface LlmExplanationRepository extends JpaRepository<LlmExplanation, Long> {

    Optional<LlmExplanation> findTopByEventIdAndLanguageAndStyleAndIncludeRecommendedActionsAndEvidenceHashAndCurrentTrue(
            String eventId, String language, String style, boolean includeRecommendedActions, String evidenceHash);

    Optional<LlmExplanation> findTopByEventIdAndCurrentTrueOrderByUpdatedAtDesc(String eventId);

    @Modifying
    @Transactional
    @Query("UPDATE LlmExplanation e SET e.current = false WHERE e.eventId = :eventId AND e.language = :language AND e.style = :style AND e.includeRecommendedActions = :includeActions AND e.current = true")
    int markPreviousAsNotCurrent(@Param("eventId") String eventId,
                                 @Param("language") String language,
                                 @Param("style") String style,
                                 @Param("includeActions") boolean includeRecommendedActions);
}
