import asyncio
import json

import pytest

from app import ai
from app.openrouter import OpenRouterError
from app.schemas import BoardData, ChatMessage
from app.database import INITIAL_COLUMNS, INITIAL_CARDS


def _initial_board() -> BoardData:
    columns = [
        {"id": column_id, "title": title, "cardIds": []}
        for column_id, title in INITIAL_COLUMNS
    ]
    cards = {}
    for card_id, title, details, column_id in INITIAL_CARDS:
        cards[card_id] = {"id": card_id, "title": title, "details": details}
        next(column for column in columns if column["id"] == column_id)["cardIds"].append(
            card_id
        )
    return BoardData.model_validate({"columns": columns, "cards": cards})


def test_respond_includes_board_history_and_applies_valid_actions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    board = _initial_board()
    history = [ChatMessage(role="assistant", content="Let's plan the release.")]
    model_response = {
        "answer": "I added a card, updated the roadmap card, and moved a task.",
        "actions": [
            {
                "type": "create_card",
                "card_id": None,
                "column_id": "col-progress",
                "title": "Prepare launch notes",
                "details": "Summarize the release.",
                "position": None,
            },
            {
                "type": "edit_card",
                "card_id": "card-1",
                "column_id": None,
                "title": "Align product roadmap",
                "details": None,
                "position": None,
            },
            {
                "type": "move_card",
                "card_id": "card-2",
                "column_id": "col-done",
                "title": None,
                "details": None,
                "position": 0,
            },
        ],
    }

    async def fake_complete(messages, *, response_format):
        assert messages[1] == history[0].model_dump()
        assert "Current Kanban board JSON:" in messages[-1]["content"]
        assert json.dumps(board.model_dump(), ensure_ascii=False) in messages[-1]["content"]
        assert "Current question:" in messages[-1]["content"]
        assert response_format["json_schema"]["strict"] is True
        return json.dumps(model_response)

    monkeypatch.setattr(ai, "complete", fake_complete)
    answer, actions = asyncio.run(
        ai.respond_to_board(board, "Prepare a release plan.", history)
    )
    updated_board = ai.apply_actions(board, actions)

    assert answer == model_response["answer"]
    assert updated_board.cards["card-1"].title == "Align product roadmap"
    assert updated_board.columns[2].cardIds[-1].startswith("ai-card-")
    assert updated_board.columns[4].cardIds[0] == "card-2"
    assert board.cards["card-1"].title == "Align roadmap themes"
    assert board.columns[0].cardIds == ["card-1", "card-2"]


def test_invalid_ai_action_rejects_all_updates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    board = _initial_board()

    async def fake_complete(_messages, *, response_format):
        return json.dumps(
            {
                "answer": "Updated the card.",
                "actions": [
                    {
                        "type": "edit_card",
                        "card_id": "not-on-this-board",
                        "column_id": None,
                        "title": "Changed",
                        "details": None,
                        "position": None,
                    }
                ],
            }
        )

    monkeypatch.setattr(ai, "complete", fake_complete)

    with pytest.raises(OpenRouterError, match="invalid board update"):
        asyncio.run(ai.respond_to_board(board, "Change a card.", []))

    assert board.cards["card-1"].title == "Align roadmap themes"


def test_invalid_structured_output_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def fake_complete(_messages, *, response_format):
        return '{"answer":"Missing actions"}'

    monkeypatch.setattr(ai, "complete", fake_complete)

    with pytest.raises(OpenRouterError, match="invalid structured response"):
        asyncio.run(ai.respond_to_board(_initial_board(), "Hello.", []))
