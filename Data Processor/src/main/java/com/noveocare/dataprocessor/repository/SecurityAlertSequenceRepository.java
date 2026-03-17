package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.SecurityAlertSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityAlertSequenceRepository extends JpaRepository<SecurityAlertSequence, Long> {
}
