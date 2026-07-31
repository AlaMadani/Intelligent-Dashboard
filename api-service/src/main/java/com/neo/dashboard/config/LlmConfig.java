package com.neo.dashboard.config;

import com.neo.dashboard.service.llm.LlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class LlmConfig {

    /**
     * Resolves the active {@link LlmProvider} bean based on the
     * {@code app.llm.provider}配置 property.
     * <p>
     * All registered {@code LlmProvider} beans are injected; the one whose
     * {@link LlmProvider#providerName()} matches the configured value is
     * returned as the {@code @Primary} bean.
     *
     * @throws IllegalStateException if no provider matches the configured name
     */
    @Bean
    @Primary
    public LlmProvider activeLlmProvider(
            @Value("${app.llm.provider:nvidia-nim}") String providerName,
            List<LlmProvider> providers
    ) {
        /* Find the first provider whose name matches the configured value */
        return providers.stream()
                .filter(p -> p.providerName().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No LLM provider found for name '" + providerName + "'. "
                                + "Available providers: " + providers.stream().map(LlmProvider::providerName).toList()));
    }
}
