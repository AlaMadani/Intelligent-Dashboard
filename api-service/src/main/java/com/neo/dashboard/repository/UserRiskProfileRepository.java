package com.neo.dashboard.repository;

import com.neo.dashboard.entity.UserRiskProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRiskProfileRepository extends JpaRepository<UserRiskProfile, Long> {

    Optional<UserRiskProfile> findByInsuredId(String insuredId);

    Page<UserRiskProfile> findAllByOrderByAnomalyRate30dDesc(Pageable pageable);

    @Query("SELECT u.riskTier, COUNT(u) FROM UserRiskProfile u WHERE u.riskTier IS NOT NULL GROUP BY u.riskTier")
    List<Object[]> countByRiskTier();
}