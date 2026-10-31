package com.kanbanstudio.ai;

import com.kanbanstudio.ai.OpenRouterClient.OpenRouterException;
import com.kanbanstudio.model.AiResponse;
import com.kanbanstudio.model.BoardAction;
import com.kanbanstudio.model.BoardData;
import com.kanbanstudio.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Asks the model about the board and returns its answer plus validated card actions. */
@Service
public class AiService {

    static final String SYSTEM_PROMPT = """
            You are the Kanban Studio assistant.
            Answer the user's question and propose only card create, edit, or move actions.
            Use only column and card IDs in the provided board. Never add, remove, reorder, or rename columns.
            For create_card, set column_id and title; set details when useful; leave card_id null.
            For edit_card, set card_id and at least one of title or details; leave column_id and position null.
            For move_card, set card_id and column_id; set position only when the user requests a specific position.
            For all unused action fields, return null. Return an empty actions array when no board change is needed.""";

    private static final String RESPONSE_FORMAT_JSON = """
            {
              "type": "json_schema",
              "json_schema": {
                "name": "kanban_response",
                "strict": true,
                "schema": {
                  "type": "object",
                  "properties": {
                    "answer": {"type": "string"},
                    "actions": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "type": {"type": "string", "enum": ["create_card", "edit_card", "move_card"]},
                          "card_id": {"type": ["string", "null"]},
                          "column_id": {"type": ["string", "null"]},
                          "title": {"type": ["string", "null"]},
                          "details": {"type": ["string", "null"]},
                          "position": {"type": ["integer", "null"]}
                        },
                        "required": ["type", "card_id", "column_id", "title", "details", "position"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "required": ["answer", "actions"],
                  "additionalProperties": false
                }
              }
            }""";

    public record Reply(String answer, List<BoardAction> actions) {}

    private final OpenRouterClient client;
    private final JsonMapper jsonMapper;
    private final JsonNode responseFormat;

    public AiService(OpenRouterClient client, JsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.responseFormat = jsonMapper.readTree(RESPONSE_FORMAT_JSON);
    }

    public Reply respond(BoardData board, String question, List<ChatMessage> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        for (ChatMessage message : history) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        messages.add(Map.of("role", "user", "content",
                "Current Kanban board JSON:\n" + jsonMapper.writeValueAsString(board)
                        + "\n\nCurrent question:\n" + question));

        String content = client.complete(messages, responseFormat);
        AiResponse response;
        try {
            response = jsonMapper.readerFor(AiResponse.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(content);
        } catch (JacksonException error) {
            throw new OpenRouterException("OpenRouter returned an invalid structured response.", error);
        }
        if (response.answer() == null
                || response.actions() == null
                || response.actions().stream().anyMatch(action -> action == null || action.type() == null)) {
            throw new OpenRouterException("OpenRouter returned an invalid structured response.");
        }
        if (response.answer().isBlank()) {
            throw new OpenRouterException("OpenRouter returned an empty answer.");
        }

        // Reject the whole reply early if any action does not fit the board it was given.
        try {
            BoardActions.apply(board, response.actions());
        } catch (IllegalArgumentException error) {
            throw new OpenRouterException("OpenRouter proposed an invalid board update.", error);
        }
        return new Reply(response.answer(), List.copyOf(response.actions()));
    }
}
