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

    private final RedisDashboardRefreshListener redisDashboardRefreshListener;

    @Value("${app.redis.pubsub.live-stats-channel:LIVE_STATS}")
    private String liveStatsChannel;

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.addMessageListener(redisDashboardRefreshListener, new PatternTopic(liveStatsChannel));
        log.info("Subscribed to Redis Pub/Sub channel: {}", liveStatsChannel);

        return container;
    }

}
