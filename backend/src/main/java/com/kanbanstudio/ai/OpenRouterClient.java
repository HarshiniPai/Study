package com.kanbanstudio.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Backend-only OpenRouter chat client. The API key is never logged or returned to callers. */
public class OpenRouterClient {

    public static final String URL = "https://openrouter.ai/api/v1/chat/completions";
    public static final String DEFAULT_MODEL = "xingchen-agi/xing4.0-29b-a4b:free";
    public static final List<String> FREE_MODELS = List.of(
            DEFAULT_MODEL,
            "nvidia/nemotron-3.5-super-vl:free",
            "dots-studio/dots-3-note-preview:free",
            "liquid/lfm-2.5-2.6b:free");
    /** OpenRouter rejects requests whose "models" fallback list has more entries than this. */
    static final int MAX_MODELS_PER_REQUEST = 3;

    public static class OpenRouterException extends RuntimeException {
        public OpenRouterException(String message) {
            super(message);
        }

        public OpenRouterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Missing or invalid local configuration, as opposed to a provider failure. */
    public static class OpenRouterConfigurationException extends OpenRouterException {
        public OpenRouterConfigurationException(String message) {
            super(message);
        }
    }

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final String model;

    public OpenRouterClient(RestClient restClient, JsonMapper jsonMapper, String apiKey, String model) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
    }

    /** Sends a chat request and returns the first choice's message content. */
    public String complete(List<Map<String, String>> messages, JsonNode responseFormat) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new OpenRouterConfigurationException(
                    "OPENROUTER_API_KEY is missing. Add it to the project root .env file.");
        }
        if (!FREE_MODELS.contains(model)) {
            throw new OpenRouterConfigurationException("OPENROUTER_MODEL must be one of the configured free models.");
        }

        // OpenRouter tries the models in order, so rate-limited providers fall back to other free models.
        List<String> models = new ArrayList<>();
        models.add(model);
        FREE_MODELS.stream()
                .filter(candidate -> !candidate.equals(model))
                .limit(MAX_MODELS_PER_REQUEST - 1)
                .forEach(models::add);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("models", models);
        payload.put("messages", messages);
        payload.put("temperature", 0.2);
        if (responseFormat != null) {
            payload.put("response_format", responseFormat);
        }

        String body;
        try {
            body = restClient.post()
                    .uri(URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Title", "Kanban Studio")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new OpenRouterException("OpenRouter rate limit or usage quota exceeded (HTTP 429). "
                        + "Check your OpenRouter account limits or try again later.", error);
            }
            throw new OpenRouterException(
                    "OpenRouter returned HTTP " + error.getStatusCode().value() + ".", error);
        } catch (ResourceAccessException error) {
            throw new OpenRouterException("Could not reach OpenRouter.", error);
        }

        JsonNode result;
        try {
            result = jsonMapper.readTree(body == null ? "" : body);
        } catch (JacksonException error) {
            throw new OpenRouterException("OpenRouter returned invalid JSON.", error);
        }
        JsonNode content = result.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) {
            throw new OpenRouterException("OpenRouter returned an invalid chat response.");
        }
        if (!content.isString() || content.asString().isEmpty()) {
            throw new OpenRouterException("OpenRouter returned an empty chat response.");
        }
        return content.asString();
    }
}
