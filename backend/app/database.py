from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime, timezone
import os
from pathlib import Path
import sqlite3
from uuid import uuid4

from app.schemas import BoardData

SCHEMA_VERSION = 1
DATABASE_PATH = Path(
    os.environ.get(
        "DATABASE_PATH",
        Path(__file__).resolve().parents[2] / "data" / "kanban.sqlite3",
    )
)

INITIAL_COLUMNS = [
    ("col-backlog", "Backlog"),
    ("col-discovery", "Discovery"),
    ("col-progress", "In Progress"),
    ("col-review", "Review"),
    ("col-done", "Done"),
]

INITIAL_CARDS = [
    (
        "card-1",
        "Align roadmap themes",
        "Draft quarterly themes with impact statements and metrics.",
        "col-backlog",
    ),
    (
        "card-2",
        "Gather customer signals",
        "Review support tags, sales notes, and churn feedback.",
        "col-backlog",
    ),
    (
        "card-3",
        "Prototype analytics view",
        "Sketch initial dashboard layout and key drill-downs.",
        "col-discovery",
    ),
    (
        "card-4",
        "Refine status language",
        "Standardize column labels and tone across the board.",
        "col-progress",
    ),
    (
        "card-5",
        "Design card layout",
        "Add hierarchy and spacing for scanning dense lists.",
        "col-progress",
    ),
    (
        "card-6",
        "QA micro-interactions",
        "Verify hover, focus, and loading states.",
        "col-review",
    ),
    (
        "card-7",
        "Ship marketing page",
        "Final copy approved and asset pack delivered.",
        "col-done",
    ),
    (
        "card-8",
        "Close onboarding sprint",
        "Document release notes and share internally.",
        "col-done",
    ),
]


@contextmanager
def database_connection(path: Path | None = None) -> Iterator[sqlite3.Connection]:
    path = DATABASE_PATH if path is None else path
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    try:
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def initialize_database(path: Path | None = None) -> None:
    path = DATABASE_PATH if path is None else path
    with database_connection(path) as connection:
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        if version > SCHEMA_VERSION:
            raise RuntimeError(
                f"Database schema version {version} is newer than supported version "
                f"{SCHEMA_VERSION}."
            )

        connection.execute("BEGIN IMMEDIATE")
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS boards (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS columns (
                board_id TEXT NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
                id TEXT NOT NULL,
                title TEXT NOT NULL CHECK (trim(title) <> ''),
                position INTEGER NOT NULL CHECK (position >= 0),
                PRIMARY KEY (board_id, id),
                UNIQUE (board_id, position)
            )
            """
        )
        connection.execute(
            """
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
            )
            """
        )
        now = _now()
        connection.execute(
            """
            INSERT OR IGNORE INTO users (id, username, created_at)
            VALUES (?, ?, ?)
            """,
            ("mvp-user", "user", now),
        )
        user = connection.execute(
            "SELECT id FROM users WHERE username = ?", ("user",)
        ).fetchone()
        board = connection.execute(
            "SELECT id FROM boards WHERE user_id = ?", (user["id"],)
        ).fetchone()
        if board is None:
            board_id = str(uuid4())
            connection.execute(
                """
                INSERT INTO boards (id, user_id, title, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (board_id, user["id"], "My Kanban Board", now, now),
            )
            connection.executemany(
                """
                INSERT INTO columns (board_id, id, title, position)
                VALUES (?, ?, ?, ?)
                """,
                [
                    (board_id, column_id, title, position)
                    for position, (column_id, title) in enumerate(INITIAL_COLUMNS)
                ],
            )
            card_positions: dict[str, int] = {}
            initial_cards = []
            for card_id, title, details, column_id in INITIAL_CARDS:
                position = card_positions.get(column_id, 0)
                initial_cards.append(
                    (
                        board_id,
                        card_id,
                        column_id,
                        title,
                        details,
                        position,
                        now,
                        now,
                    )
                )
                card_positions[column_id] = position + 1
            connection.executemany(
                """
                INSERT INTO cards (
                    board_id, id, column_id, title, details, position,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                initial_cards,
            )
        connection.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")


def get_user_id(username: str) -> str | None:
    with database_connection() as connection:
        row = connection.execute(
            "SELECT id FROM users WHERE username = ?", (username,)
        ).fetchone()
        return row["id"] if row is not None else None


def get_board(user_id: str) -> dict[str, object] | None:
    with database_connection() as connection:
        board = connection.execute(
            "SELECT id FROM boards WHERE user_id = ?", (user_id,)
        ).fetchone()
        if board is None:
            return None

        board_id = board["id"]
        columns = connection.execute(
            """
            SELECT id, title FROM columns
            WHERE board_id = ? ORDER BY position
            """,
            (board_id,),
        ).fetchall()
        cards = connection.execute(
            """
            SELECT id, column_id, title, details FROM cards
            WHERE board_id = ? ORDER BY column_id, position
            """,
            (board_id,),
        ).fetchall()

        column_data = [
            {"id": column["id"], "title": column["title"], "cardIds": []}
            for column in columns
        ]
        columns_by_id = {column["id"]: column for column in column_data}
        card_data: dict[str, dict[str, str]] = {}
        for card in cards:
            card_data[card["id"]] = {
                "id": card["id"],
                "title": card["title"],
                "details": card["details"],
            }
            columns_by_id[card["column_id"]]["cardIds"].append(card["id"])

        return {"columns": column_data, "cards": card_data}


def save_board(user_id: str, board_data: BoardData) -> dict[str, object]:
    with database_connection() as connection:
        board = connection.execute(
            "SELECT id FROM boards WHERE user_id = ?", (user_id,)
        ).fetchone()
        if board is None:
            raise LookupError("Board not found for signed-in user.")
        board_id = board["id"]

        current_columns = connection.execute(
            """
            SELECT id FROM columns WHERE board_id = ? ORDER BY position
            """,
            (board_id,),
        ).fetchall()
        expected_column_ids = [column["id"] for column in current_columns]
        received_column_ids = [column.id for column in board_data.columns]
        if received_column_ids != expected_column_ids:
            raise ValueError("The fixed board columns cannot be added, removed, or reordered.")

        for column in board_data.columns:
            if not column.title.strip():
                raise ValueError("Column titles cannot be empty.")
        for card_id, card in board_data.cards.items():
            if card_id != card.id:
                raise ValueError("Each card key must match its ID.")
            if not card_id.strip() or not card.title.strip():
                raise ValueError("Card IDs and titles cannot be empty.")

        ordered_card_ids = [
            card_id for column in board_data.columns for card_id in column.cardIds
        ]
        if len(ordered_card_ids) != len(set(ordered_card_ids)):
            raise ValueError("A card can appear in only one column position.")
        if set(ordered_card_ids) != set(board_data.cards):
            raise ValueError("Every card must appear exactly once in a board column.")

        existing_cards = connection.execute(
            "SELECT id, created_at FROM cards WHERE board_id = ?", (board_id,)
        ).fetchall()
        created_at_by_id = {card["id"]: card["created_at"] for card in existing_cards}
        now = _now()

        connection.execute(
            "UPDATE boards SET updated_at = ? WHERE id = ?", (now, board_id)
        )
        connection.executemany(
            "UPDATE columns SET title = ? WHERE board_id = ? AND id = ?",
            [
                (column.title, board_id, column.id)
                for column in board_data.columns
            ],
        )
        connection.execute("DELETE FROM cards WHERE board_id = ?", (board_id,))
        connection.executemany(
            """
            INSERT INTO cards (
                board_id, id, column_id, title, details, position,
                created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    board_id,
                    card_id,
                    column.id,
                    board_data.cards[card_id].title,
                    board_data.cards[card_id].details,
                    position,
                    created_at_by_id.get(card_id, now),
                    now,
                )
                for column in board_data.columns
                for position, card_id in enumerate(column.cardIds)
            ],
        )
        return board_data.model_dump()
