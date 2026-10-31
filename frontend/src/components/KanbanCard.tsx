import { useState, type FormEvent } from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import clsx from "clsx";
import type { Card } from "@/lib/kanban";

type KanbanCardProps = {
  card: Card;
  onDelete: (cardId: string) => void;
  onEdit: (cardId: string, title: string, details: string) => void;
  disabled: boolean;
};

export const KanbanCard = ({
  card,
  onDelete,
  onEdit,
  disabled,
}: KanbanCardProps) => {
  const [isEditing, setIsEditing] = useState(false);
  const [title, setTitle] = useState(card.title);
  const [details, setDetails] = useState(card.details);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: card.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!title.trim()) {
      return;
    }
    onEdit(card.id, title.trim(), details.trim());
    setIsEditing(false);
  };

  return (
    <article
      ref={setNodeRef}
      style={style}
      className={clsx(
        "rounded-2xl border border-transparent bg-white px-4 py-4 shadow-[0_12px_24px_rgba(3,33,71,0.08)]",
        "transition-all duration-150",
        isDragging && "opacity-60 shadow-[0_18px_32px_rgba(3,33,71,0.16)]"
      )}
      {...attributes}
      {...listeners}
      data-testid={`card-${card.id}`}
    >
      {isEditing ? (
        <form onSubmit={handleSubmit} className="space-y-3">
          <input
            aria-label="Edit card title"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            disabled={disabled}
            required
            className="w-full rounded-xl border border-[var(--stroke)] px-3 py-2 text-sm font-medium text-[var(--navy-dark)]"
          />
          <textarea
            aria-label="Edit card details"
            value={details}
            onChange={(event) => setDetails(event.target.value)}
            disabled={disabled}
            rows={3}
            className="w-full resize-none rounded-xl border border-[var(--stroke)] px-3 py-2 text-sm text-[var(--gray-text)]"
          />
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={disabled}
              className="rounded-full bg-[var(--secondary-purple)] px-3 py-2 text-xs font-semibold text-white"
            >
              Save changes
            </button>
            <button
              type="button"
              disabled={disabled}
              onClick={() => {
                setTitle(card.title);
                setDetails(card.details);
                setIsEditing(false);
              }}
              className="rounded-full border border-[var(--stroke)] px-3 py-2 text-xs font-semibold text-[var(--gray-text)]"
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="flex items-start justify-between gap-3">
          <div>
            <h4 className="font-display text-base font-semibold text-[var(--navy-dark)]">
              {card.title}
            </h4>
            <p className="mt-2 text-sm leading-6 text-[var(--gray-text)]">
              {card.details}
            </p>
          </div>
          <div className="flex flex-col gap-1">
            <button
              type="button"
              disabled={disabled}
              onPointerDown={(event) => event.stopPropagation()}
              onClick={() => {
                // Start from the latest card, which may have changed since mount (e.g. an AI update).
                setTitle(card.title);
                setDetails(card.details);
                setIsEditing(true);
              }}
              className="rounded-full border border-transparent px-2 py-1 text-xs font-semibold text-[var(--primary-blue)] transition hover:border-[var(--stroke)]"
              aria-label={`Edit ${card.title}`}
            >
              Edit
            </button>
            <button
              type="button"
              disabled={disabled}
              onPointerDown={(event) => event.stopPropagation()}
              onClick={() => onDelete(card.id)}
              className="rounded-full border border-transparent px-2 py-1 text-xs font-semibold text-[var(--gray-text)] transition hover:border-[var(--stroke)] hover:text-[var(--navy-dark)]"
              aria-label={`Delete ${card.title}`}
            >
              Remove
            </button>
          </div>
        </div>
      )}
    </article>
  );
};
