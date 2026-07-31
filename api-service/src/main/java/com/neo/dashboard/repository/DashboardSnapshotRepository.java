package com.neo.dashboard.repository;

import com.neo.dashboard.entity.DashboardSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link DashboardSnapshot} entities.
 * Snapshots capture the state of a dashboard view at a point in time,
 * enabling rollback or comparison features in the UI.
 */
@Repository
public interface DashboardSnapshotRepository extends JpaRepository<DashboardSnapshot, Long> {

    /** Looks up a specific snapshot by its view name and unique snapshot key. */
    Optional<DashboardSnapshot> findByViewNameAndSnapshotKey(String viewName, String snapshotKey);

    /** Returns the most recent snapshot taken for a given view. */
    Optional<DashboardSnapshot> findTopByViewNameOrderBySnapshotTimestampDesc(String viewName);
}
