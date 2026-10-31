import json
from uuid import uuid4

from pydantic import ValidationError

from app.openrouter import OpenRouterError, complete
from app.schemas import AIResponse, BoardAction, BoardData, Card, ChatMessage

RESPONSE_FORMAT: dict[str, object] = {
    "type": "json_schema",
    "json_schema": {
        "name": "kanban_response",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "answer": {"type": "string"},
                "actions": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "type": {
                                "type": "string",
                                "enum": ["create_card", "edit_card", "move_card"],
                            },
                            "card_id": {
                                "type": ["string", "null"],
                            },
                            "column_id": {
                                "type": ["string", "null"],
                            },
                            "title": {
                                "type": ["string", "null"],
                            },
                            "details": {
                                "type": ["string", "null"],
                            },
                            "position": {
                                "type": ["integer", "null"],
                            },
                        },
                        "required": [
                            "type",
                            "card_id",
                            "column_id",
                            "title",
                            "details",
                            "position",
                        ],
                        "additionalProperties": False,
                    },
                },
            },
            "required": ["answer", "actions"],
            "additionalProperties": False,
        },
    },
}

SYSTEM_PROMPT = """You are the Kanban Studio assistant.
Answer the user's question and propose only card create, edit, or move actions.
Use only column and card IDs in the provided board. Never add, remove, reorder, or rename columns.
For create_card, set column_id and title; set details when useful; leave card_id null.
For edit_card, set card_id and at least one of title or details; leave column_id and position null.
For move_card, set card_id and column_id; set position only when the user requests a specific position.
For all unused action fields, return null. Return an empty actions array when no board change is needed."""


def _insert_position(position: int | None, length: int) -> int:
    if position is None:
        return length
    if position < 0 or position > length:
        raise ValueError("AI requested a card position outside the column.")
    return position


def _apply_action(board: BoardData, action: BoardAction) -> None:
    if action.type == "create_card":
        if action.card_id is not None or not action.column_id or not action.title:
            raise ValueError("Create action is missing required fields.")
        if not action.title.strip():
            raise ValueError("Created card title cannot be empty.")
        column = next(
            (column for column in board.columns if column.id == action.column_id),
            None,
        )
        if column is None:
            raise ValueError("Create action references an unknown column.")
        card_id = f"ai-card-{uuid4().hex}"
        position = _insert_position(action.position, len(column.cardIds))
        board.cards[card_id] = Card(
            id=card_id,
            title=action.title.strip(),
            details=action.details or "No details yet.",
        )
        column.cardIds.insert(position, card_id)
        return

    if action.type == "edit_card":
        if (
            not action.card_id
            or action.column_id is not None
            or action.position is not None
            or (action.title is None and action.details is None)
        ):
            raise ValueError("Edit action is missing required fields.")
        card = board.cards.get(action.card_id)
        if card is None:
            raise ValueError("Edit action references an unknown card.")
        if action.title is not None and not action.title.strip():
            raise ValueError("Edited card title cannot be empty.")
        board.cards[action.card_id] = card.model_copy(
            update={
                "title": action.title.strip() if action.title is not None else card.title,
                "details": action.details if action.details is not None else card.details,
            }
        )
        return

    if (
        not action.card_id
        or not action.column_id
        or action.title is not None
        or action.details is not None
    ):
        raise ValueError("Move action is missing required fields.")
    source = next(
        (column for column in board.columns if action.card_id in column.cardIds),
        None,
    )
    target = next(
        (column for column in board.columns if column.id == action.column_id),
        None,
    )
    if source is None or target is None:
        raise ValueError("Move action references an unknown card or column.")

    source.cardIds.remove(action.card_id)
    position = _insert_position(action.position, len(target.cardIds))
    target.cardIds.insert(position, action.card_id)


def apply_actions(board: BoardData, actions: list[BoardAction]) -> BoardData:
    """Return a copy of the board with all actions applied; raise ValueError if any is invalid."""
    updated_board = board.model_copy(deep=True)
    for action in actions:
        _apply_action(updated_board, action)
    return updated_board


async def respond_to_board(
    board: BoardData,
    question: str,
    history: list[ChatMessage],
) -> tuple[str, list[BoardAction]]:
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        *[message.model_dump() for message in history],
        {
            "role": "user",
            "content": (
                "Current Kanban board JSON:\n"
                f"{json.dumps(board.model_dump(), ensure_ascii=False)}\n\n"
                f"Current question:\n{question}"
            ),
        },
    ]
    content = await complete(messages, response_format=RESPONSE_FORMAT)
    try:
        response = AIResponse.model_validate_json(content)
    except ValidationError as error:
        raise OpenRouterError("OpenRouter returned an invalid structured response.") from error
    if not response.answer.strip():
        raise OpenRouterError("OpenRouter returned an empty answer.")

    try:
        apply_actions(board, response.actions)
    except ValueError as error:
        raise OpenRouterError("OpenRouter proposed an invalid board update.") from error
    return response.answer, response.actions
