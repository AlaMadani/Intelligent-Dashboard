package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.DashboardSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for dashboard snapshot cache entries.
 */
@Repository
public interface DashboardSnapshotRepository extends JpaRepository<DashboardSnapshot, Long> {

    /* Retrieve the latest snapshot for a specific view and key combination. */
    Optional<DashboardSnapshot> findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(String viewName, String snapshotKey);

    /* Retrieve the latest snapshot for a view regardless of key. */
    Optional<DashboardSnapshot> findTopByViewNameOrderBySnapshotTimestampDesc(String viewName);
}
