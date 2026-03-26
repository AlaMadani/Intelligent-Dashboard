package com.neo.dashboard.repository;

import com.neo.dashboard.entity.SecurityAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecurityAlertRepository extends JpaRepository<SecurityAlert, Long> {

    /* Fetch alerts for a user ordered by most recent detection time. */
    List<SecurityAlert> findByUserKeyOrderByDetectedAtDesc(Integer userKey);

    /* Return user keys with the highest alert counts. */
    @Query("SELECT s.userKey, COUNT(s.id) as total FROM SecurityAlert s GROUP BY s.userKey ORDER BY total DESC LIMIT 5")
    List<Object[]> findTopTargetedUsers();
}
