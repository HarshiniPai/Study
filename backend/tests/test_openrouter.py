import asyncio
import json

import httpx
import pytest

from app.openrouter import (
    DEFAULT_MODEL,
    FREE_MODELS,
    MAX_MODELS_PER_REQUEST,
    OpenRouterConfigurationError,
    OpenRouterError,
    complete,
)


def test_complete_sends_authorized_chat_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    monkeypatch.delenv("OPENROUTER_MODEL", raising=False)
    requests: list[httpx.Request] = []

    def handle_request(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "4"}}]},
        )

    answer = asyncio.run(
        complete(
            [{"role": "user", "content": "What is 2 + 2?"}],
            transport=httpx.MockTransport(handle_request),
        )
    )

    assert answer == "4"
    assert len(requests) == 1
    assert requests[0].headers["Authorization"] == "Bearer test-key"
    body = json.loads(requests[0].content)
    assert body["models"] == list(FREE_MODELS[:MAX_MODELS_PER_REQUEST])
    assert body["messages"] == [{"role": "user", "content": "What is 2 + 2?"}]


def test_complete_prioritizes_configured_free_model(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    monkeypatch.setenv("OPENROUTER_MODEL", FREE_MODELS[1])
    requests: list[httpx.Request] = []

    def handle_request(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "4"}}]},
        )

    asyncio.run(
        complete(
            [{"role": "user", "content": "What is 2 + 2?"}],
            transport=httpx.MockTransport(handle_request),
        )
    )

    models = json.loads(requests[0].content)["models"]
    assert models[0] == FREE_MODELS[1]
    assert len(models) == len(set(models)) == MAX_MODELS_PER_REQUEST
    assert set(models) <= set(FREE_MODELS)


def test_complete_rejects_non_free_model(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    monkeypatch.setenv("OPENROUTER_MODEL", "provider/paid-model")

    with pytest.raises(OpenRouterConfigurationError, match="configured free models"):
        asyncio.run(complete([{"role": "user", "content": "Hello"}]))


def test_complete_sends_structured_output_configuration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    response_format = {
        "type": "json_schema",
        "json_schema": {"name": "board_response", "strict": True, "schema": {}},
    }

    def handle_request(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["response_format"] == response_format
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "{\"answer\":\"4\"}"}}]},
        )

    answer = asyncio.run(
        complete(
            [{"role": "user", "content": "What is 2 + 2?"}],
            response_format=response_format,
            transport=httpx.MockTransport(handle_request),
        )
    )

    assert answer == '{"answer":"4"}'


def test_complete_requires_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OPENROUTER_API_KEY", raising=False)

    with pytest.raises(OpenRouterConfigurationError, match="OPENROUTER_API_KEY"):
        asyncio.run(complete([{"role": "user", "content": "Hello"}]))


def test_complete_reports_provider_errors(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")

    def handle_request(_: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": {"message": "rate limited"}})

    with pytest.raises(OpenRouterError, match="rate limit or usage quota"):
        asyncio.run(
            complete(
                [{"role": "user", "content": "Hello"}],
                transport=httpx.MockTransport(handle_request),
            )
        )
