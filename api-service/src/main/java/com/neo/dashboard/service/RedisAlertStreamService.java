package com.neo.dashboard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Configures the Redis Pub/Sub listener container for dashboard refresh notifications.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "app.redis.pubsub.enabled", havingValue = "true", matchIfMissing = true)
public class RedisAlertStreamService {

    /** Listener that handles incoming Pub/Sub messages and bridges them into SSE streams. */
    private final RedisDashboardRefreshListener redisDashboardRefreshListener;

    /** Redis channel name to subscribe to for live-stats notifications; defaults to {@code LIVE_STATS}. */
    @Value("${app.redis.pubsub.live-stats-channel:LIVE_STATS}")
    private String liveStatsChannel;

    /**
     * Creates and configures the Redis message listener container that subscribes
     * to the live-stats Pub/Sub channel and dispatches messages to the dashboard
     * refresh listener.
     *
     * @param connectionFactory the Redis connection factory
     * @return a fully-configured {@link RedisMessageListenerContainer}
     */
    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        /* Register the listener on the configured channel pattern. */
        container.addMessageListener(redisDashboardRefreshListener, new PatternTopic(liveStatsChannel));
        log.info("Subscribed to Redis Pub/Sub channel: {}", liveStatsChannel);

        return container;
    }

}
