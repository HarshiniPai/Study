import json
import os
from pathlib import Path

import httpx
from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parents[2]
load_dotenv(PROJECT_ROOT / ".env")

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
DEFAULT_MODEL = "xingchen-agi/xing4.0-29b-a4b:free"
FREE_MODELS = (
    DEFAULT_MODEL,
    "nvidia/nemotron-3.5-super-vl:free",
    "dots-studio/dots-3-note-preview:free",
    "liquid/lfm-2.5-2.6b:free",
)


class OpenRouterError(Exception):
    pass


class OpenRouterConfigurationError(OpenRouterError):
    pass


async def complete(
    messages: list[dict[str, str]],
    *,
    response_format: dict[str, object] | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> str:
    api_key = os.getenv("OPENROUTER_API_KEY")
    if not api_key:
        raise OpenRouterConfigurationError(
            "OPENROUTER_API_KEY is missing. Add it to the project root .env file."
        )

    model = os.getenv("OPENROUTER_MODEL", DEFAULT_MODEL)
    if model not in FREE_MODELS:
        raise OpenRouterConfigurationError(
            "OPENROUTER_MODEL must be one of the configured free models."
        )

    payload: dict[str, object] = {
        "models": [
            model,
            *(candidate for candidate in FREE_MODELS if candidate != model),
        ],
        "messages": messages,
        "temperature": 0.2,
    }
    if response_format is not None:
        payload["response_format"] = response_format

    try:
        async with httpx.AsyncClient(
            transport=transport,
            timeout=httpx.Timeout(45.0),
        ) as client:
            response = await client.post(
                OPENROUTER_URL,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                    "X-Title": "Kanban Studio",
                },
                json=payload,
            )
            response.raise_for_status()
            result = response.json()
    except httpx.HTTPStatusError as error:
        if error.response.status_code == 429:
            raise OpenRouterError(
                "OpenRouter rate limit or usage quota exceeded (HTTP 429). "
                "Check your OpenRouter account limits or try again later."
            ) from error
        raise OpenRouterError(
            f"OpenRouter returned HTTP {error.response.status_code}."
        ) from error
    except httpx.RequestError as error:
        raise OpenRouterError("Could not reach OpenRouter.") from error
    except json.JSONDecodeError as error:
        raise OpenRouterError("OpenRouter returned invalid JSON.") from error

    try:
        content = result["choices"][0]["message"]["content"]
    except (IndexError, KeyError, TypeError) as error:
        raise OpenRouterError("OpenRouter returned an invalid chat response.") from error
    if not isinstance(content, str) or not content:
        raise OpenRouterError("OpenRouter returned an empty chat response.")
    return content
