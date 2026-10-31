package com.kanbanstudio.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.kanbanstudio.ai.OpenRouterClient.OpenRouterConfigurationException;
import com.kanbanstudio.ai.OpenRouterClient.OpenRouterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenRouterClientTest {

    private static final List<Map<String, String>> QUESTION =
            List.of(Map.of("role", "user", "content", "What is 2 + 2?"));
    private static final String ANSWER_FOUR = "{\"choices\":[{\"message\":{\"content\":\"4\"}}]}";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final List<JsonNode> requestBodies = new ArrayList<>();

    private OpenRouterClient client(String apiKey, String model) {
        return new OpenRouterClient(builder.build(), jsonMapper, apiKey, model);
    }

    private void expectRequest(String responseBody) {
        server.expect(requestTo(OpenRouterClient.URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(request -> requestBodies.add(
                        jsonMapper.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    private List<String> sentModels() {
        List<String> models = new ArrayList<>();
        requestBodies.getFirst().path("models").forEach(model -> models.add(model.asString()));
        return models;
    }

    @Test
    void completeSendsAuthorizedChatRequest() {
        expectRequest(ANSWER_FOUR);

        String answer = client("test-key", "").complete(QUESTION, null);

        assertThat(answer).isEqualTo("4");
        server.verify();
        assertThat(sentModels()).isEqualTo(OpenRouterClient.FREE_MODELS.subList(0, 3));
        assertThat(requestBodies.getFirst().path("messages"))
                .isEqualTo(jsonMapper.readTree("[{\"role\":\"user\",\"content\":\"What is 2 + 2?\"}]"));
        assertThat(requestBodies.getFirst().has("response_format")).isFalse();
    }

    @Test
    void completePrioritizesConfiguredFreeModel() {
        expectRequest(ANSWER_FOUR);

        client("test-key", OpenRouterClient.FREE_MODELS.get(1)).complete(QUESTION, null);

        assertThat(sentModels().getFirst()).isEqualTo(OpenRouterClient.FREE_MODELS.get(1));
        assertThat(sentModels()).hasSize(OpenRouterClient.MAX_MODELS_PER_REQUEST).doesNotHaveDuplicates();
        assertThat(OpenRouterClient.FREE_MODELS).containsAll(sentModels());
    }

    @Test
    void completeRejectsNonFreeModel() {
        assertThatThrownBy(() -> client("test-key", "provider/paid-model").complete(QUESTION, null))
                .isInstanceOf(OpenRouterConfigurationException.class)
                .hasMessageContaining("configured free models");
    }

    @Test
    void completeSendsStructuredOutputConfiguration() {
        JsonNode responseFormat = jsonMapper.readTree(
                "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"board_response\",\"strict\":true,\"schema\":{}}}");
        expectRequest("{\"choices\":[{\"message\":{\"content\":\"{\\\"answer\\\":\\\"4\\\"}\"}}]}");

        String answer = client("test-key", "").complete(QUESTION, responseFormat);

        assertThat(answer).isEqualTo("{\"answer\":\"4\"}");
        assertThat(requestBodies.getFirst().path("response_format")).isEqualTo(responseFormat);
    }

    @Test
    void completeRequiresApiKey() {
        assertThatThrownBy(() -> client("", "").complete(QUESTION, null))
                .isInstanceOf(OpenRouterConfigurationException.class)
                .hasMessageContaining("OPENROUTER_API_KEY");
    }

    @Test
    void completeReportsRateLimits() {
        server.expect(requestTo(OpenRouterClient.URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"rate limited\"}}"));

        assertThatThrownBy(() -> client("test-key", "").complete(QUESTION, null))
                .isInstanceOf(OpenRouterException.class)
                .hasMessageContaining("rate limit or usage quota");
    }

    @Test
    void completeRejectsResponsesWithoutContent() {
        expectRequest("{\"choices\":[]}");

        assertThatThrownBy(() -> client("test-key", "").complete(QUESTION, null))
                .isInstanceOf(OpenRouterException.class)
                .hasMessageContaining("invalid chat response");
    }
}
