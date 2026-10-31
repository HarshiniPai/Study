package com.kanbanstudio.ai;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class AiConfig {

    @Bean
    OpenRouterClient openRouterClient(
            JsonMapper jsonMapper,
            @Value("${OPENROUTER_API_KEY:}") String apiKey,
            @Value("${OPENROUTER_MODEL:}") String model) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(45));
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return new OpenRouterClient(restClient, jsonMapper, apiKey, model);
    }
}
