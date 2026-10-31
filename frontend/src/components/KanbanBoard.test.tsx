import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { KanbanBoard } from "@/components/KanbanBoard";
import { initialData, type BoardData } from "@/lib/kanban";

const getFirstColumn = () => screen.getAllByTestId(/column-/i)[0];

const jsonResponse = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });

describe("KanbanBoard", () => {
  let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

  beforeEach(() => {
    fetchMock = vi.fn<typeof fetch>().mockImplementation(async (_input, init) => {
      if (init?.method === "PUT") {
        return jsonResponse(JSON.parse(String(init.body)) as BoardData);
      }
      return jsonResponse(initialData);
    });
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads five columns", async () => {
    render(<KanbanBoard />);
    expect(await screen.findAllByTestId(/column-/i)).toHaveLength(5);
  });

  it("loads the board and persists a column rename", async () => {
    render(<KanbanBoard />);
    expect(await screen.findAllByTestId(/column-/i)).toHaveLength(5);
    const column = getFirstColumn();
    const input = within(column).getByLabelText("Column title");
    const user = userEvent.setup();
    await user.clear(input);
    await user.type(input, "New Name");
    fireEvent.blur(input);
    expect(input).toHaveValue("New Name");
    await waitFor(() => {
      expect(fetchMock).toHaveBeenLastCalledWith(
        "/api/board",
        expect.objectContaining({ method: "PUT" })
      );
    });
  });

  it("adds, edits, and removes a card with persisted updates", async () => {
    render(<KanbanBoard />);
    const column = await screen.findByTestId("column-col-backlog");
    expect(within(column).getByText("Align roadmap themes")).toBeInTheDocument();
    const user = userEvent.setup();
    const addButton = within(column).getByRole("button", {
      name: /add a card/i,
    });
    await user.click(addButton);

    const titleInput = within(column).getByPlaceholderText(/card title/i);
    await user.type(titleInput, "New card");
    const detailsInput = within(column).getByPlaceholderText(/details/i);
    await user.type(detailsInput, "Notes");

    await user.click(within(column).getByRole("button", { name: /add card/i }));

    const editButton = await within(column).findByRole("button", {
      name: "Edit New card",
    });
    const newCard = editButton.closest("article");
    if (!newCard) {
      throw new Error("Unable to find the new card container.");
    }
    expect(within(newCard).getByText("New card")).toBeInTheDocument();
    await user.click(editButton);
    await user.clear(within(newCard).getByLabelText("Edit card title"));
    await user.type(within(newCard).getByLabelText("Edit card title"), "Updated card");
    await user.click(within(newCard).getByRole("button", { name: "Save changes" }));
    expect(await within(newCard).findByText("Updated card")).toBeInTheDocument();

    const deleteButton = within(column).getByRole("button", {
      name: /delete updated card/i,
    });
    await user.click(deleteButton);

    expect(within(column).queryByText("Updated card")).not.toBeInTheDocument();
  });

  it("reverts optimistic changes and shows an error when saving fails", async () => {
    fetchMock.mockReset();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(initialData))
      .mockResolvedValueOnce(jsonResponse({ detail: "Database unavailable" }, 500));
    render(<KanbanBoard />);
    const column = await screen.findByTestId("column-col-backlog");
    const user = userEvent.setup();
    await user.click(await within(column).findByRole("button", { name: /add a card/i }));
    await user.type(within(column).getByPlaceholderText(/card title/i), "Unsaved");
    await user.click(within(column).getByRole("button", { name: /add card/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Could not save board (500). Changes were reverted."
    );
    expect(within(column).queryByText("Unsaved")).not.toBeInTheDocument();
  });
});
