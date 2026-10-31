import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AIChatSidebar } from "@/components/AIChatSidebar";
import { initialData } from "@/lib/kanban";

const response = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });

describe("AIChatSidebar", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends conversation history and refreshes the board after an update", async () => {
    const updatedBoard = structuredClone(initialData);
    updatedBoard.cards["card-1"].title = "Updated by assistant";
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        response({
          answer: "I updated the roadmap card.",
          board_updated: true,
          board: updatedBoard,
        })
      )
      .mockResolvedValueOnce(
        response({
          answer: "The backlog has two cards.",
          board_updated: false,
          board: updatedBoard,
        })
      );
    vi.stubGlobal("fetch", fetchMock);
    const onBoardUpdated = vi.fn();
    const user = userEvent.setup();

    render(<AIChatSidebar onBoardUpdated={onBoardUpdated} />);
    const input = screen.getByLabelText("Message");
    await user.type(input, "Rename the roadmap card.");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByText("I updated the roadmap card.")).toBeVisible();
    expect(onBoardUpdated).toHaveBeenCalledOnce();

    await user.type(input, "How many backlog cards?");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("The backlog has two cards.")).toBeVisible();

    await waitFor(() => {
      const request = fetchMock.mock.calls[1][1];
      expect(JSON.parse(String(request?.body))).toEqual({
        question: "How many backlog cards?",
        history: [
          { role: "user", content: "Rename the roadmap card." },
          { role: "assistant", content: "I updated the roadmap card." },
        ],
      });
    });
    expect(onBoardUpdated).toHaveBeenCalledOnce();
  });

  it("shows provider errors without hiding the conversation", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        response({ detail: "OpenRouter is unavailable." }, 502)
      )
    );
    const user = userEvent.setup();

    render(<AIChatSidebar onBoardUpdated={vi.fn()} />);
    await user.type(screen.getByLabelText("Message"), "Move a card.");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "OpenRouter is unavailable."
    );
    expect(screen.getByText("Move a card.")).toBeVisible();
  });
});
