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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHealth() {
        boolean redisOk = true;
        boolean dbOk = true;
        try {
            redisTemplate.opsForValue().get("health:ping");
        } catch (Exception e) {
            redisOk = false;
        }
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            dbOk = false;
        }
        String status = (redisOk && dbOk) ? "UP" : "DEGRADED";
        return ResponseEntity.ok(ApiResponse.of(Map.of(
                "status", status,
                "checks", Map.of("redis", redisOk ? "UP" : "DOWN", "database", dbOk ? "UP" : "DOWN"),
                "timestamp", Instant.now().toString()
        )));
    }
}