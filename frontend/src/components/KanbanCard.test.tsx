import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DndContext } from "@dnd-kit/core";
import { SortableContext } from "@dnd-kit/sortable";
import { KanbanCard } from "@/components/KanbanCard";

const renderCard = (title: string, details: string) => (
  <DndContext>
    <SortableContext items={["card-1"]}>
      <KanbanCard
        card={{ id: "card-1", title, details }}
        onDelete={() => {}}
        onEdit={() => {}}
        disabled={false}
      />
    </SortableContext>
  </DndContext>
);

describe("KanbanCard", () => {
  it("opens the edit form with the latest card values after the card changes", async () => {
    const { rerender } = render(renderCard("Old title", "Old details"));
    rerender(renderCard("AI title", "AI details"));

    await userEvent.click(screen.getByRole("button", { name: "Edit AI title" }));

    expect(screen.getByLabelText("Edit card title")).toHaveValue("AI title");
    expect(screen.getByLabelText("Edit card details")).toHaveValue("AI details");
  });
});
