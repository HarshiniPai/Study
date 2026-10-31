from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import database
from app.main import app
from app.openrouter import OpenRouterConfigurationError
from app.schemas import BoardAction, BoardData


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    monkeypatch.setattr(database, "DATABASE_PATH", tmp_path / "kanban.sqlite3")
    with TestClient(app) as test_client:
        yield test_client


def test_health_check(client: TestClient) -> None:
    response = client.get("/api/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_homepage_serves_exported_board(client: TestClient) -> None:
    response = client.get("/")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/html")
    assert "Kanban Studio" in response.text


def test_board_requires_sign_in(client: TestClient) -> None:
    response = client.get("/api/board")

    assert response.status_code == 401


def test_board_access_is_scoped_to_signed_in_user(client: TestClient) -> None:
    client.post("/api/auth/login", json={"username": "user", "password": "password"})
    signed_in_user_id = database.get_user_id("user")
    assert signed_in_user_id is not None
    with database.database_connection() as connection:
        connection.execute(
            "INSERT INTO users (id, username, created_at) VALUES (?, ?, ?)",
            ("other-user", "other", "2026-01-01T00:00:00Z"),
        )
        connection.execute(
            """
            INSERT INTO boards (id, user_id, title, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                "other-board",
                "other-user",
                "Other board",
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z",
            ),
        )

    response = client.get("/api/board", params={"user_id": "other-user"})

    assert response.status_code == 200
    assert response.json() == database.get_board(signed_in_user_id)


def test_authenticated_user_can_read_and_update_board(client: TestClient) -> None:
    client.post("/api/auth/login", json={"username": "user", "password": "password"})

    board_response = client.get("/api/board")
    assert board_response.status_code == 200
    board = board_response.json()
    assert len(board["columns"]) == 5
    assert len(board["cards"]) == 8

    board["columns"][0]["title"] = "Incoming"
    board["columns"][0]["cardIds"].reverse()
    update_response = client.put("/api/board", json=board)
    assert update_response.status_code == 200
    assert update_response.json()["columns"][0]["title"] == "Incoming"

    persisted_board = client.get("/api/board").json()
    assert persisted_board["columns"][0]["title"] == "Incoming"
    assert persisted_board["columns"][0]["cardIds"] == ["card-2", "card-1"]


def test_invalid_board_update_does_not_change_persisted_data(
    client: TestClient,
) -> None:
    client.post("/api/auth/login", json={"username": "user", "password": "password"})
    original_board = client.get("/api/board").json()
    invalid_board = {**original_board, "columns": original_board["columns"][:-1]}

    response = client.put("/api/board", json=invalid_board)

    assert response.status_code == 400
    assert client.get("/api/board").json() == original_board


def test_initialization_is_idempotent_and_preserves_updates(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(database, "DATABASE_PATH", tmp_path / "kanban.sqlite3")
    database.initialize_database()
    user_id = database.get_user_id("user")
    assert user_id is not None
    board = BoardData.model_validate(database.get_board(user_id))
    board.columns[0].title = "Renamed"
    database.save_board(user_id, board)

    database.initialize_database()

    assert database.get_board(user_id)["columns"][0]["title"] == "Renamed"


def test_ai_chat_applies_and_persists_board_updates(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client.post("/api/auth/login", json={"username": "user", "password": "password"})
    calls: list[tuple[str, list[dict[str, str]]]] = []

    async def fake_respond(board, question, history):
        calls.append((question, [message.model_dump() for message in history]))
        return "I updated the card.", [
            BoardAction(type="edit_card", card_id="card-1", title="Updated by assistant")
        ]

    monkeypatch.setattr("app.main.respond_to_board", fake_respond)
    response = client.post(
        "/api/ai/chat",
        json={
            "question": "Rename the roadmap task.",
            "history": [{"role": "assistant", "content": "What should change?"}],
        },
    )

    assert response.status_code == 200
    assert response.json()["answer"] == "I updated the card."
    assert response.json()["board_updated"] is True
    assert response.json()["board"]["cards"]["card-1"]["title"] == "Updated by assistant"
    assert client.get("/api/board").json()["cards"]["card-1"]["title"] == "Updated by assistant"
    assert calls == [
        (
            "Rename the roadmap task.",
            [{"role": "assistant", "content": "What should change?"}],
        )
    ]


def test_ai_chat_keeps_edits_saved_while_assistant_responds(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client.post("/api/auth/login", json={"username": "user", "password": "password"})
    user_id = database.get_user_id("user")

    async def slow_respond(board, _question, _history):
        # Simulate the user renaming a column while the AI request is in flight.
        edited = board.model_copy(deep=True)
        edited.columns[4].title = "Shipped"
        database.save_board(user_id, edited)
        return "Added a card.", [
            BoardAction(type="create_card", column_id="col-backlog", title="AI card")
        ]

    monkeypatch.setattr("app.main.respond_to_board", slow_respond)
    response = client.post("/api/ai/chat", json={"question": "Add a card."})

    assert response.status_code == 200
    saved = client.get("/api/board").json()
    assert saved["columns"][4]["title"] == "Shipped"
    assert "AI card" in [card["title"] for card in saved["cards"].values()]
    assert response.json()["board"] == saved


def test_ai_chat_rejects_actions_for_cards_deleted_meanwhile(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client.post("/api/auth/login", json={"username": "user", "password": "password"})
    user_id = database.get_user_id("user")

    async def slow_respond(board, _question, _history):
        edited = board.model_copy(deep=True)
        del edited.cards["card-1"]
        edited.columns[0].cardIds.remove("card-1")
        database.save_board(user_id, edited)
        return "Renamed it.", [
            BoardAction(type="edit_card", card_id="card-1", title="Renamed")
        ]

    monkeypatch.setattr("app.main.respond_to_board", slow_respond)
    response = client.post("/api/ai/chat", json={"question": "Rename card 1."})

    assert response.status_code == 409
    assert "card-1" not in client.get("/api/board").json()["cards"]


def test_ai_chat_requires_authentication(client: TestClient) -> None:
    response = client.post("/api/ai/chat", json={"question": "Create a card."})

    assert response.status_code == 401


def test_ai_chat_reports_missing_key_without_affecting_board(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client.post("/api/auth/login", json={"username": "user", "password": "password"})

    async def missing_key(_board, _question, _history):
        raise OpenRouterConfigurationError("OPENROUTER_API_KEY is missing.")

    monkeypatch.setattr("app.main.respond_to_board", missing_key)
    before = client.get("/api/board").json()

    response = client.post("/api/ai/chat", json={"question": "Move a card."})

    assert response.status_code == 503
    assert "OPENROUTER_API_KEY" in response.json()["detail"]
    assert client.get("/api/board").json() == before


def test_login_logout_flow(client: TestClient) -> None:
    assert client.get("/api/auth/me").json() == {
        "authenticated": False,
        "username": None,
    }

    invalid_login = client.post(
        "/api/auth/login",
        json={"username": "user", "password": "wrong"},
    )
    assert invalid_login.status_code == 401

    login = client.post(
        "/api/auth/login",
        json={"username": "user", "password": "password"},
    )
    assert login.status_code == 200
    assert client.get("/api/auth/me").json() == {
        "authenticated": True,
        "username": "user",
    }

    logout = client.post("/api/auth/logout")
    assert logout.status_code == 204
    assert client.get("/api/auth/me").json() == {
        "authenticated": False,
        "username": None,
    }
