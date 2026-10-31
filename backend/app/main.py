from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
import secrets
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from starlette.middleware.sessions import SessionMiddleware

from app import database
from app.ai import apply_actions, respond_to_board
from app.openrouter import OpenRouterConfigurationError, OpenRouterError
from app.schemas import BoardData, ChatRequest, ChatResponse

STATIC_DIR = Path(__file__).resolve().parents[2] / "frontend" / "out"
MVP_USERNAME = "user"
MVP_PASSWORD = "password"


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    database.initialize_database()
    yield


app = FastAPI(title="Project Management MVP API", lifespan=lifespan)


class LoginRequest(BaseModel):
    username: str
    password: str


@app.get("/api/health")
def health_check() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/auth/me")
def get_session(request: Request) -> dict[str, bool | str | None]:
    username = request.session.get("username")
    authenticated = username == MVP_USERNAME
    return {
        "authenticated": authenticated,
        "username": username if authenticated else None,
    }


@app.post("/api/auth/login")
def login(payload: LoginRequest, request: Request) -> dict[str, str]:
    if payload.username != MVP_USERNAME or payload.password != MVP_PASSWORD:
        raise HTTPException(status_code=401, detail="Invalid username or password")

    request.session.clear()
    request.session["username"] = MVP_USERNAME
    return {"username": MVP_USERNAME}


@app.post("/api/auth/logout", status_code=204)
def logout(request: Request) -> Response:
    request.session.clear()
    return Response(status_code=204)


def signed_in_user_id(request: Request) -> str:
    username = request.session.get("username")
    if username != MVP_USERNAME:
        raise HTTPException(status_code=401, detail="Sign in required.")
    user_id = database.get_user_id(username)
    if user_id is None:
        raise HTTPException(status_code=401, detail="Signed-in user not found.")
    return user_id


@app.get("/api/board", response_model=BoardData)
def read_board(request: Request) -> dict[str, object]:
    user_id = signed_in_user_id(request)
    board = database.get_board(user_id)
    if board is None:
        raise HTTPException(status_code=404, detail="Board not found.")
    return board


@app.put("/api/board", response_model=BoardData)
def update_board(board_data: BoardData, request: Request) -> dict[str, object]:
    user_id = signed_in_user_id(request)
    try:
        return database.save_board(user_id, board_data)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    except LookupError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error


@app.post("/api/ai/chat", response_model=ChatResponse)
async def ai_chat(payload: ChatRequest, request: Request) -> ChatResponse:
    user_id = signed_in_user_id(request)
    if not payload.question.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty.")
    board_data = database.get_board(user_id)
    if board_data is None:
        raise HTTPException(status_code=404, detail="Board not found.")
    board = BoardData.model_validate(board_data)

    try:
        answer, actions = await respond_to_board(
            board,
            payload.question,
            payload.history,
        )
    except OpenRouterConfigurationError as error:
        raise HTTPException(status_code=503, detail=str(error)) from error
    except OpenRouterError as error:
        raise HTTPException(status_code=502, detail=str(error)) from error

    # The AI call can take a while, so apply its actions to the latest saved board
    # rather than the earlier snapshot, or edits saved in the meantime would be lost.
    current_board = BoardData.model_validate(database.get_board(user_id))
    try:
        updated_board = apply_actions(current_board, actions)
    except ValueError as error:
        raise HTTPException(
            status_code=409,
            detail="The board changed while the assistant was responding. Try again.",
        ) from error

    board_updated = updated_board != current_board
    if board_updated:
        try:
            database.save_board(user_id, updated_board)
        except ValueError as error:
            raise HTTPException(
                status_code=502,
                detail="The AI update could not be applied to the board.",
            ) from error
    return ChatResponse(
        answer=answer,
        board=updated_board,
        board_updated=board_updated,
    )


app.add_middleware(
    SessionMiddleware,
    secret_key=secrets.token_urlsafe(32),
    same_site="lax",
)
app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="frontend")
