package com.noveocare.dataprocessor.repository;

import com.noveocare.dataprocessor.entity.DashboardSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DashboardSnapshotRepository extends JpaRepository<DashboardSnapshot, Long> {

    Optional<DashboardSnapshot> findTopByViewNameAndSnapshotKeyOrderBySnapshotTimestampDesc(String viewName, String snapshotKey);

    Optional<DashboardSnapshot> findTopByViewNameOrderBySnapshotTimestampDesc(String viewName);
}
