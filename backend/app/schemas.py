from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class Card(BaseModel):
    id: str
    title: str
    details: str


class BoardColumn(BaseModel):
    id: str
    title: str
    cardIds: list[str]


class BoardData(BaseModel):
    columns: list[BoardColumn]
    cards: dict[str, Card]


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str


class ChatRequest(BaseModel):
    question: str = Field(min_length=1)
    history: list[ChatMessage] = Field(default_factory=list)


class BoardAction(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["create_card", "edit_card", "move_card"]
    card_id: str | None = None
    column_id: str | None = None
    title: str | None = None
    details: str | None = None
    position: int | None = None


class AIResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer: str
    actions: list[BoardAction]


class ChatResponse(BaseModel):
    answer: str
    board: BoardData
    board_updated: bool
