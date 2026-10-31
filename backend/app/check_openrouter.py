import asyncio

from app.openrouter import complete


async def check_connectivity() -> None:
    answer = await complete(
        [
            {
                "role": "user",
                "content": "What is 2 + 2? Reply with only the number.",
            }
        ]
    )
    if answer.strip().strip("`").rstrip(".!") != "4":
        raise RuntimeError("The OpenRouter connectivity check did not return 4.")
    print("OpenRouter connectivity check passed (2+2=4).")


if __name__ == "__main__":
    asyncio.run(check_connectivity())
