package com.neo.dashboard.config;

import com.neo.dashboard.service.llm.LlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class LlmConfig {

    @Bean
    @Primary
    public LlmProvider activeLlmProvider(
            @Value("${app.llm.provider:nvidia-nim}") String providerName,
            List<LlmProvider> providers
    ) {
        return providers.stream()
                .filter(p -> p.providerName().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No LLM provider found for name '" + providerName + "'. "
                                + "Available providers: " + providers.stream().map(LlmProvider::providerName).toList()));
    }
}
