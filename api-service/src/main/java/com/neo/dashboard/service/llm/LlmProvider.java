package com.neo.dashboard.service.llm;

public interface LlmProvider {

    LlmProviderResponse generate(LlmProviderRequest request);

    String providerName();
}
