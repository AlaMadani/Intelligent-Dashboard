package com.neo.dashboard.repository;

import com.neo.dashboard.entity.UserRiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRiskProfileRepository extends JpaRepository<UserRiskProfile, Long> {
    Optional<UserRiskProfile> findByInsuredId(String insuredId);
}
