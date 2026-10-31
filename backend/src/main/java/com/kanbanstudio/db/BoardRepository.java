package com.kanbanstudio.db;

import com.kanbanstudio.model.BoardColumn;
import com.kanbanstudio.model.BoardData;
import com.kanbanstudio.model.Card;
import jakarta.annotation.PostConstruct;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** SQLite schema, seeding, and board queries. See docs/DATABASE.md. */
@Repository
public class BoardRepository {

    public static final int SCHEMA_VERSION = 1;
    public static final String MVP_USER_ID = "mvp-user";
    public static final String MVP_USERNAME = "user";

    public record SeedColumn(String id, String title) {}

    public record SeedCard(String id, String title, String details, String columnId) {}

    public static final List<SeedColumn> INITIAL_COLUMNS = List.of(
            new SeedColumn("col-backlog", "Backlog"),
            new SeedColumn("col-discovery", "Discovery"),
            new SeedColumn("col-progress", "In Progress"),
            new SeedColumn("col-review", "Review"),
            new SeedColumn("col-done", "Done"));

    public static final List<SeedCard> INITIAL_CARDS = List.of(
            new SeedCard("card-1", "Align roadmap themes",
                    "Draft quarterly themes with impact statements and metrics.", "col-backlog"),
            new SeedCard("card-2", "Gather customer signals",
                    "Review support tags, sales notes, and churn feedback.", "col-backlog"),
            new SeedCard("card-3", "Prototype analytics view",
                    "Sketch initial dashboard layout and key drill-downs.", "col-discovery"),
            new SeedCard("card-4", "Refine status language",
                    "Standardize column labels and tone across the board.", "col-progress"),
            new SeedCard("card-5", "Design card layout",
                    "Add hierarchy and spacing for scanning dense lists.", "col-progress"),
            new SeedCard("card-6", "QA micro-interactions",
                    "Verify hover, focus, and loading states.", "col-review"),
            new SeedCard("card-7", "Ship marketing page",
                    "Final copy approved and asset pack delivered.", "col-done"),
            new SeedCard("card-8", "Close onboarding sprint",
                    "Document release notes and share internally.", "col-done"));

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    /** The submitted board breaks a board invariant. */
    public static class InvalidBoardException extends RuntimeException {
        InvalidBoardException(String message) {
            super(message);
        }
    }

    /** The user has no board. */
    public static class BoardNotFoundException extends RuntimeException {
        BoardNotFoundException(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public BoardRepository(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    private static String now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(TIMESTAMP);
    }

    /** Creates missing tables and seeds the MVP user and board without overwriting existing data. */
    @PostConstruct
    public void initialize() {
        transaction.executeWithoutResult(status -> {
            Integer version = jdbc.queryForObject("PRAGMA user_version", Integer.class);
            if (version != null && version > SCHEMA_VERSION) {
                throw new IllegalStateException("Database schema version " + version
                        + " is newer than supported version " + SCHEMA_VERSION + ".");
            }

            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL
                    )""");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS boards (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
                        title TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )""");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS columns (
                        board_id TEXT NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
                        id TEXT NOT NULL,
                        title TEXT NOT NULL CHECK (trim(title) <> ''),
                        position INTEGER NOT NULL CHECK (position >= 0),
                        PRIMARY KEY (board_id, id),
                        UNIQUE (board_id, position)
                    )""");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS cards (
                        board_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        column_id TEXT NOT NULL,
                        title TEXT NOT NULL CHECK (trim(title) <> ''),
                        details TEXT NOT NULL,
                        position INTEGER NOT NULL CHECK (position >= 0),
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (board_id, id),
                        UNIQUE (board_id, column_id, position),
                        FOREIGN KEY (board_id, column_id)
                            REFERENCES columns(board_id, id) ON DELETE CASCADE
                    )""");

            String now = now();
            jdbc.update("INSERT OR IGNORE INTO users (id, username, created_at) VALUES (?, ?, ?)",
                    MVP_USER_ID, MVP_USERNAME, now);
            String userId = jdbc.queryForObject(
                    "SELECT id FROM users WHERE username = ?", String.class, MVP_USERNAME);
            List<String> boards = jdbc.queryForList(
                    "SELECT id FROM boards WHERE user_id = ?", String.class, userId);
            if (boards.isEmpty()) {
                seedBoard(userId, now);
            }
            jdbc.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        });
    }

    private void seedBoard(String userId, String now) {
        String boardId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO boards (id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                boardId, userId, "My Kanban Board", now, now);

        List<Object[]> columns = new ArrayList<>();
        for (int position = 0; position < INITIAL_COLUMNS.size(); position++) {
            SeedColumn column = INITIAL_COLUMNS.get(position);
            columns.add(new Object[] {boardId, column.id(), column.title(), position});
        }
        jdbc.batchUpdate("INSERT INTO columns (board_id, id, title, position) VALUES (?, ?, ?, ?)", columns);

        Map<String, Integer> nextPosition = new HashMap<>();
        List<Object[]> cards = new ArrayList<>();
        for (SeedCard card : INITIAL_CARDS) {
            int position = nextPosition.merge(card.columnId(), 1, Integer::sum) - 1;
            cards.add(new Object[] {
                boardId, card.id(), card.columnId(), card.title(), card.details(), position, now, now
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO cards (board_id, id, column_id, title, details, position, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", cards);
    }

    public Optional<String> findUserId(String username) {
        return jdbc.queryForList("SELECT id FROM users WHERE username = ?", String.class, username)
                .stream()
                .findFirst();
    }

    private Optional<String> findBoardId(String userId) {
        return jdbc.queryForList("SELECT id FROM boards WHERE user_id = ?", String.class, userId)
                .stream()
                .findFirst();
    }

    public Optional<BoardData> getBoard(String userId) {
        return transaction.execute(status -> findBoardId(userId).map(this::loadBoard));
    }

    private BoardData loadBoard(String boardId) {
        List<BoardColumn> columns = new ArrayList<>();
        Map<String, BoardColumn> columnsById = new HashMap<>();
        jdbc.query("SELECT id, title FROM columns WHERE board_id = ? ORDER BY position",
                (RowCallbackHandler) row -> {
                    BoardColumn column = new BoardColumn(row.getString("id"), row.getString("title"), new ArrayList<>());
                    columns.add(column);
                    columnsById.put(column.id(), column);
                },
                boardId);

        Map<String, Card> cards = new LinkedHashMap<>();
        jdbc.query("SELECT id, column_id, title, details FROM cards WHERE board_id = ? ORDER BY column_id, position",
                (RowCallbackHandler) row -> {
                    String cardId = row.getString("id");
                    cards.put(cardId, new Card(cardId, row.getString("title"), row.getString("details")));
                    columnsById.get(row.getString("column_id")).cardIds().add(cardId);
                },
                boardId);
        return new BoardData(columns, cards);
    }

    /** Replaces the user's board contents after checking the board invariants. */
    public BoardData saveBoard(String userId, BoardData board) {
        return transaction.execute(status -> {
            String boardId = findBoardId(userId)
                    .orElseThrow(() -> new BoardNotFoundException("Board not found for signed-in user."));

            List<String> expectedColumnIds = jdbc.queryForList(
                    "SELECT id FROM columns WHERE board_id = ? ORDER BY position", String.class, boardId);
            List<String> receivedColumnIds = board.columns().stream().map(BoardColumn::id).toList();
            if (!receivedColumnIds.equals(expectedColumnIds)) {
                throw new InvalidBoardException("The fixed board columns cannot be added, removed, or reordered.");
            }
            for (BoardColumn column : board.columns()) {
                if (column.title().isBlank()) {
                    throw new InvalidBoardException("Column titles cannot be empty.");
                }
            }
            for (Map.Entry<String, Card> entry : board.cards().entrySet()) {
                if (!entry.getKey().equals(entry.getValue().id())) {
                    throw new InvalidBoardException("Each card key must match its ID.");
                }
                if (entry.getKey().isBlank() || entry.getValue().title().isBlank()) {
                    throw new InvalidBoardException("Card IDs and titles cannot be empty.");
                }
            }

            List<String> orderedCardIds = board.columns().stream()
                    .flatMap(column -> column.cardIds().stream())
                    .toList();
            Set<String> uniqueCardIds = new HashSet<>(orderedCardIds);
            if (uniqueCardIds.size() != orderedCardIds.size()) {
                throw new InvalidBoardException("A card can appear in only one column position.");
            }
            if (!uniqueCardIds.equals(board.cards().keySet())) {
                throw new InvalidBoardException("Every card must appear exactly once in a board column.");
            }

            Map<String, String> createdAtById = new HashMap<>();
            jdbc.query("SELECT id, created_at FROM cards WHERE board_id = ?",
                    (RowCallbackHandler) row -> createdAtById.put(row.getString("id"), row.getString("created_at")),
                    boardId);
            String now = now();

            jdbc.update("UPDATE boards SET updated_at = ? WHERE id = ?", now, boardId);
            jdbc.batchUpdate("UPDATE columns SET title = ? WHERE board_id = ? AND id = ?",
                    board.columns().stream()
                            .map(column -> new Object[] {column.title(), boardId, column.id()})
                            .toList());
            jdbc.update("DELETE FROM cards WHERE board_id = ?", boardId);

            List<Object[]> rows = new ArrayList<>();
            for (BoardColumn column : board.columns()) {
                for (int position = 0; position < column.cardIds().size(); position++) {
                    Card card = board.cards().get(column.cardIds().get(position));
                    rows.add(new Object[] {
                        boardId, card.id(), column.id(), card.title(), card.details(), position,
                        createdAtById.getOrDefault(card.id(), now), now
                    });
                }
            }
            jdbc.batchUpdate("""
                    INSERT INTO cards (board_id, id, column_id, title, details, position, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", rows);
            return board;
        });
    }
}
