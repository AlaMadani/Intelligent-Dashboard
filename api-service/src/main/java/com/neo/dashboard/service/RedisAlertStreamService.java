package com.neo.dashboard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Configures the Redis Pub/Sub listener container and registers the alert listener.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "app.redis.pubsub.enabled", havingValue = "true", matchIfMissing = true)
public class RedisAlertStreamService {

    private final RedisAlertListener redisAlertListener;

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        // Subscribe to the critical alert channels
        for (String channel : redisAlertListener.getAlertChannels()) {
            container.addMessageListener(redisAlertListener, new PatternTopic(channel));
            log.info("Subscribed to Redis Pub/Sub channel: {}", channel);
        }
        
        return container;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
