package com.neo.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test that verifies the Spring application context can start with the
 * current configuration.
 */
@SpringBootTest(properties = "app.redis.pubsub.enabled=false")
class ApiServiceApplicationTests {

    /* Fail fast if bean wiring or auto-configuration breaks. */
    @Test
    void contextLoads() {
    }
}
