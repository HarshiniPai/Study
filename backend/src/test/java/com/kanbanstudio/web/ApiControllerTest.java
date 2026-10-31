package com.kanbanstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kanbanstudio.ai.AiService;
import com.kanbanstudio.ai.OpenRouterClient.OpenRouterConfigurationException;
import com.kanbanstudio.db.BoardRepository;
import com.kanbanstudio.model.BoardAction;
import com.kanbanstudio.model.BoardColumn;
import com.kanbanstudio.model.BoardData;
import com.kanbanstudio.model.ChatMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.hamcrest.Matchers.containsString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "OPENROUTER_API_KEY=")
class ApiControllerTest {

    private static final Path TEST_DIR = Path.of("target", "test-data");

    @DynamicPropertySource
    static void testPaths(DynamicPropertyRegistry registry) {
        try {
            Path staticDir = Files.createDirectories(TEST_DIR.resolve("static"));
            Files.writeString(staticDir.resolve("index.html"), "<!doctype html><title>Kanban Studio</title>");
            Files.deleteIfExists(TEST_DIR.resolve("kanban.sqlite3"));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        registry.add("app.database-path", () -> TEST_DIR.resolve("kanban.sqlite3").toString());
        registry.add("app.static-dir", () -> TEST_DIR.resolve("static").toAbsolutePath().toString());
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private AiService aiService;

    private MockMvc mvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        // Start each test from a freshly seeded board; deleting users cascades to their data.
        jdbc.update("DELETE FROM users");
        boards.initialize();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        session = new MockHttpSession();
    }

    private MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request) {
        return request.session(session);
    }

    /** Signs in and keeps the new session, since login replaces any existing one. */
    private void signIn() throws Exception {
        session = (MockHttpSession) mvc.perform(withSession(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    private JsonNode getBoardJson() throws Exception {
        String body = mvc.perform(withSession(get("/api/board")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return jsonMapper.readTree(body);
    }

    private String userId() {
        return boards.findUserId("user").orElseThrow();
    }

    private BoardData savedBoard() {
        return boards.getBoard(userId()).orElseThrow();
    }

    @Test
    void healthCheck() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @Test
    void homepageServesExportedFrontend() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Kanban Studio")));
    }

    @Test
    void boardRequiresSignIn() throws Exception {
        mvc.perform(get("/api/board")).andExpect(status().isUnauthorized());
    }

    @Test
    void boardAccessIsScopedToSignedInUser() throws Exception {
        signIn();
        String now = "2026-01-01T00:00:00Z";
        jdbc.update("INSERT INTO users (id, username, created_at) VALUES (?, ?, ?)", "other-user", "other", now);
        jdbc.update("INSERT INTO boards (id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                "other-board", "other-user", "Other board", now, now);

        String body = mvc.perform(withSession(get("/api/board").param("user_id", "other-user")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(jsonMapper.readValue(body, BoardData.class)).isEqualTo(savedBoard());
    }

    @Test
    void authenticatedUserCanReadAndUpdateBoard() throws Exception {
        signIn();
        JsonNode board = getBoardJson();
        assertThat(board.path("columns").size()).isEqualTo(5);
        assertThat(board.path("cards").size()).isEqualTo(8);

        ObjectNode firstColumn = (ObjectNode) board.path("columns").path(0);
        firstColumn.put("title", "Incoming");
        firstColumn.putArray("cardIds").add("card-2").add("card-1");

        mvc.perform(withSession(put("/api/board"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(board)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].title").value("Incoming"));

        JsonNode persisted = getBoardJson();
        assertThat(persisted.path("columns").path(0).path("title").asString()).isEqualTo("Incoming");
        assertThat(savedBoard().columns().getFirst().cardIds()).containsExactly("card-2", "card-1");
    }

    @Test
    void invalidBoardUpdateDoesNotChangePersistedData() throws Exception {
        signIn();
        JsonNode original = getBoardJson();
        ObjectNode invalid = (ObjectNode) original.deepCopy();
        invalid.withArray("columns").remove(4);

        mvc.perform(withSession(put("/api/board"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The fixed board columns cannot be added, removed, or reordered."));

        assertThat(getBoardJson()).isEqualTo(original);
    }

    @Test
    void malformedBoardIsRejected() throws Exception {
        signIn();

        mvc.perform(withSession(put("/api/board"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"id\":\"col-backlog\"}],\"cards\":{}}"))
                .andExpect(status().is(422));
    }

    @Test
    void foreignKeysCascadeFromUsersToBoardData() {
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        jdbc.update("DELETE FROM users");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cards", Integer.class)).isZero();
    }

    @Test
    void initializationIsIdempotentAndPreservesUpdates() {
        BoardData board = savedBoard().copy();
        var first = board.columns().getFirst();
        board.columns().set(0, new BoardColumn(first.id(), "Renamed", first.cardIds()));
        boards.saveBoard(userId(), board);

        boards.initialize();

        assertThat(savedBoard().columns().getFirst().title()).isEqualTo("Renamed");
    }

    @Test
    void aiChatAppliesAndPersistsBoardUpdates() throws Exception {
        signIn();
        when(aiService.respond(any(), any(), anyList())).thenReturn(new AiService.Reply("I updated the card.", List.of(
                new BoardAction(BoardAction.Type.EDIT_CARD, "card-1", null, "Updated by assistant", null, null))));

        mvc.perform(withSession(post("/api/ai/chat"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "Rename the roadmap task.",
                                 "history": [{"role": "assistant", "content": "What should change?"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("I updated the card."))
                .andExpect(jsonPath("$.board_updated").value(true))
                .andExpect(jsonPath("$.board.cards.card-1.title").value("Updated by assistant"));

        assertThat(savedBoard().cards().get("card-1").title()).isEqualTo("Updated by assistant");
        verify(aiService).respond(any(), eq("Rename the roadmap task."),
                eq(List.of(new ChatMessage("assistant", "What should change?"))));
    }

    @Test
    void aiChatKeepsEditsSavedWhileAssistantResponds() throws Exception {
        signIn();
        when(aiService.respond(any(), any(), anyList())).thenAnswer(invocation -> {
            // Simulate the user renaming a column while the AI request is in flight.
            BoardData edited = ((BoardData) invocation.getArgument(0)).copy();
            var done = edited.columns().get(4);
            edited.columns().set(4, new BoardColumn(done.id(), "Shipped", done.cardIds()));
            boards.saveBoard(userId(), edited);
            return new AiService.Reply("Added a card.", List.of(
                    new BoardAction(BoardAction.Type.CREATE_CARD, null, "col-backlog", "AI card", null, null)));
        });

        String body = mvc.perform(withSession(post("/api/ai/chat"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Add a card.\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        BoardData saved = savedBoard();
        assertThat(saved.columns().get(4).title()).isEqualTo("Shipped");
        assertThat(saved.cards().values()).anyMatch(card -> card.title().equals("AI card"));
        assertThat(jsonMapper.readValue(jsonMapper.readTree(body).path("board").toString(), BoardData.class))
                .isEqualTo(saved);
    }

    @Test
    void aiChatRejectsActionsForCardsDeletedMeanwhile() throws Exception {
        signIn();
        when(aiService.respond(any(), any(), anyList())).thenAnswer(invocation -> {
            BoardData edited = ((BoardData) invocation.getArgument(0)).copy();
            edited.cards().remove("card-1");
            edited.columns().getFirst().cardIds().remove("card-1");
            boards.saveBoard(userId(), edited);
            return new AiService.Reply("Renamed it.", List.of(
                    new BoardAction(BoardAction.Type.EDIT_CARD, "card-1", null, "Renamed", null, null)));
        });

        mvc.perform(withSession(post("/api/ai/chat"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Rename card 1.\"}"))
                .andExpect(status().isConflict());

        assertThat(savedBoard().cards()).doesNotContainKey("card-1");
    }

    @Test
    void aiChatRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Create a card.\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aiChatReportsMissingKeyWithoutAffectingBoard() throws Exception {
        signIn();
        when(aiService.respond(any(), any(), anyList()))
                .thenThrow(new OpenRouterConfigurationException("OPENROUTER_API_KEY is missing."));
        BoardData before = savedBoard();

        mvc.perform(withSession(post("/api/ai/chat"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"Move a card.\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("OPENROUTER_API_KEY")));

        assertThat(savedBoard()).isEqualTo(before);
    }

    @Test
    void loginLogoutFlow() throws Exception {
        mvc.perform(withSession(get("/api/auth/me")))
                .andExpect(content().json("{\"authenticated\":false,\"username\":null}"));

        mvc.perform(withSession(post("/api/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        signIn();
        mvc.perform(withSession(get("/api/auth/me")))
                .andExpect(content().json("{\"authenticated\":true,\"username\":\"user\"}"));

        mvc.perform(withSession(post("/api/auth/logout"))).andExpect(status().isNoContent());
        mvc.perform(withSession(get("/api/auth/me")))
                .andExpect(content().json("{\"authenticated\":false,\"username\":null}"));
    }
}
