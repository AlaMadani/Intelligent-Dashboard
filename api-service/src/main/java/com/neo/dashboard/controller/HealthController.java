package com.neo.dashboard.controller;

import com.neo.dashboard.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * REST controller for system health checks.
 * Probes critical infrastructure dependencies (Redis cache and database)
 * and reports overall service health status.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    /** Redis template used to verify cache connectivity via a ping operation. */
    private final StringRedisTemplate redisTemplate;

    /** JDBC template used to verify database connectivity via a lightweight query. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Performs a health check against Redis and the primary database.
     * Returns UP only when both dependencies are reachable, otherwise DEGRADED.
     * Individual check results (UP/DOWN) are included for each dependency.
     *
     * @return health status map with overall status, per-component checks, and a timestamp
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHealth() {
        /* Probe Redis connectivity with a lightweight get operation */
        boolean redisOk = true;
        boolean dbOk = true;
        try {
            redisTemplate.opsForValue().get("health:ping");
        } catch (Exception e) {
            redisOk = false;
        }
        /* Probe database connectivity with a simple SELECT 1 query */
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            dbOk = false;
        }
        /* Derive overall status: both healthy = UP, otherwise DEGRADED */
        String status = (redisOk && dbOk) ? "UP" : "DEGRADED";
        /* Assemble the response with component-level health details */
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "status", status,
                "checks", Map.of("redis", redisOk ? "UP" : "DOWN", "database", dbOk ? "UP" : "DOWN"),
                "timestamp", Instant.now().toString()
        )));
    }
}