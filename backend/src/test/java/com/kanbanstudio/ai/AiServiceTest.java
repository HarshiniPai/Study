package com.kanbanstudio.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kanbanstudio.ai.OpenRouterClient.OpenRouterException;
import com.kanbanstudio.db.BoardRepository;
import com.kanbanstudio.model.BoardColumn;
import com.kanbanstudio.model.BoardData;
import com.kanbanstudio.model.Card;
import com.kanbanstudio.model.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AiServiceTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OpenRouterClient client = mock(OpenRouterClient.class);
    private final AiService service = new AiService(client, jsonMapper);

    private static BoardData initialBoard() {
        List<BoardColumn> columns = new ArrayList<>();
        for (BoardRepository.SeedColumn column : BoardRepository.INITIAL_COLUMNS) {
            columns.add(new BoardColumn(column.id(), column.title(), new ArrayList<>()));
        }
        Map<String, Card> cards = new LinkedHashMap<>();
        for (BoardRepository.SeedCard card : BoardRepository.INITIAL_CARDS) {
            cards.put(card.id(), new Card(card.id(), card.title(), card.details()));
            columns.stream().filter(column -> column.id().equals(card.columnId())).findFirst().orElseThrow()
                    .cardIds().add(card.id());
        }
        return new BoardData(columns, cards);
    }

    private static String action(String type, String cardId, String columnId, String title, Integer position) {
        return """
                {"type": %s, "card_id": %s, "column_id": %s, "title": %s, "details": null, "position": %s}"""
                .formatted(quote(type), quote(cardId), quote(columnId), quote(title), position);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    void respondIncludesBoardAndHistoryAndReturnsValidActions() {
        BoardData board = initialBoard();
        List<ChatMessage> history = List.of(new ChatMessage("assistant", "Let's plan the release."));
        String modelResponse = """
                {"answer": "I added a card, updated the roadmap card, and moved a task.", "actions": [%s, %s, %s]}"""
                .formatted(
                        action("create_card", null, "col-progress", "Prepare launch notes", null),
                        action("edit_card", "card-1", null, "Align product roadmap", null),
                        action("move_card", "card-2", "col-done", null, 0));

        when(client.complete(anyList(), any())).thenAnswer(invocation -> {
            List<Map<String, String>> messages = invocation.getArgument(0);
            JsonNode format = invocation.getArgument(1);
            assertThat(messages.get(1)).isEqualTo(Map.of("role", "assistant", "content", "Let's plan the release."));
            String last = messages.getLast().get("content");
            assertThat(last).contains("Current Kanban board JSON:")
                    .contains(jsonMapper.writeValueAsString(board))
                    .contains("Current question:\nPrepare a release plan.");
            assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
            return modelResponse;
        });

        AiService.Reply reply = service.respond(board, "Prepare a release plan.", history);
        BoardData updated = BoardActions.apply(board, reply.actions());

        assertThat(reply.answer()).isEqualTo("I added a card, updated the roadmap card, and moved a task.");
        assertThat(updated.cards().get("card-1").title()).isEqualTo("Align product roadmap");
        assertThat(updated.columns().get(2).cardIds().getLast()).startsWith("ai-card-");
        assertThat(updated.columns().get(4).cardIds().getFirst()).isEqualTo("card-2");
        assertThat(board.cards().get("card-1").title()).isEqualTo("Align roadmap themes");
        assertThat(board.columns().getFirst().cardIds()).containsExactly("card-1", "card-2");
    }

    @Test
    void invalidAiActionRejectsAllUpdates() {
        BoardData board = initialBoard();
        when(client.complete(anyList(), any())).thenReturn("""
                {"answer": "Updated the card.", "actions": [%s]}"""
                .formatted(action("edit_card", "not-on-this-board", null, "Changed", null)));

        assertThatThrownBy(() -> service.respond(board, "Change a card.", List.of()))
                .isInstanceOf(OpenRouterException.class)
                .hasMessageContaining("invalid board update");
        assertThat(board.cards().get("card-1").title()).isEqualTo("Align roadmap themes");
    }

    @Test
    void invalidStructuredOutputIsRejected() {
        when(client.complete(anyList(), any())).thenReturn("{\"answer\":\"Missing actions\"}");

        assertThatThrownBy(() -> service.respond(initialBoard(), "Hello.", List.of()))
                .isInstanceOf(OpenRouterException.class)
                .hasMessageContaining("invalid structured response");
    }

    @Test
    void unknownFieldsAndActionTypesAreRejected() {
        when(client.complete(anyList(), any()))
                .thenReturn("{\"answer\":\"Hi\",\"actions\":[],\"extra\":1}")
                .thenReturn("{\"answer\":\"Hi\",\"actions\":[" + action("delete_card", "card-1", null, null, null) + "]}");

        assertThatThrownBy(() -> service.respond(initialBoard(), "Hello.", List.of()))
                .hasMessageContaining("invalid structured response");
        assertThatThrownBy(() -> service.respond(initialBoard(), "Hello.", List.of()))
                .hasMessageContaining("invalid structured response");
    }
}
