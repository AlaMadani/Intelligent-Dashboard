package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.UserRiskProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRiskProfileRepository extends JpaRepository<UserRiskProfile, Long> {
    Optional<UserRiskProfile> findByInsuredId(String insuredId);
}
