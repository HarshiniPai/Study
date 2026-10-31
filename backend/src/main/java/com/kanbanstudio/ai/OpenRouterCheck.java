package com.kanbanstudio.ai;

import java.util.List;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Opt-in live check: run with --app.check-openrouter=true --spring.main.web-application-type=none. */
@Component
@ConditionalOnProperty(name = "app.check-openrouter", havingValue = "true")
class OpenRouterCheck implements CommandLineRunner {

    private final OpenRouterClient client;

    OpenRouterCheck(OpenRouterClient client) {
        this.client = client;
    }

    @Override
    public void run(String... args) {
        String answer = client.complete(
                List.of(Map.of("role", "user", "content", "What is 2 + 2? Reply with only the number.")), null);
        String normalized = answer.strip().replaceAll("^`+|`+$", "").replaceAll("[.!]+$", "");
        if (!normalized.equals("4")) {
            throw new IllegalStateException("The OpenRouter connectivity check did not return 4.");
        }
        System.out.println("OpenRouter connectivity check passed (2+2=4).");
    }
}
