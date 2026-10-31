"use client";

import { useState, type FormEvent } from "react";
import { isBoardData } from "@/lib/kanban";

type Message = {
  role: "user" | "assistant";
  content: string;
};

type ChatResponse = {
  answer: string;
  board_updated: boolean;
  board: unknown;
};

type AIChatSidebarProps = {
  onBoardUpdated: () => void;
};

const isChatResponse = (value: unknown): value is ChatResponse => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  return (
    "answer" in value &&
    typeof value.answer === "string" &&
    "board_updated" in value &&
    typeof value.board_updated === "boolean" &&
    "board" in value &&
    isBoardData(value.board)
  );
};

export const AIChatSidebar = ({ onBoardUpdated }: AIChatSidebarProps) => {
  const [messages, setMessages] = useState<Message[]>([]);
  const [question, setQuestion] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const currentQuestion = question.trim();
    if (!currentQuestion || isSending) {
      return;
    }

    const history = messages;
    setMessages((current) => [
      ...current,
      { role: "user", content: currentQuestion },
    ]);
    setQuestion("");
    setErrorMessage("");
    setIsSending(true);

    try {
      const response = await fetch("/api/ai/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ question: currentQuestion, history }),
      });
      const data: unknown = await response.json();

      if (!response.ok) {
        const detail =
          typeof data === "object" &&
          data !== null &&
          "detail" in data &&
          typeof data.detail === "string"
            ? data.detail
            : `AI request failed (${response.status}).`;
        throw new Error(detail);
      }
      if (!isChatResponse(data)) {
        throw new Error("The AI service returned an invalid response.");
      }

      setMessages((current) => [
        ...current,
        { role: "assistant", content: data.answer },
      ]);
      if (data.board_updated) {
        onBoardUpdated();
      }
    } catch (error) {
      setErrorMessage(
        error instanceof Error
          ? error.message
          : "Could not reach the AI service. Try again."
      );
    } finally {
      setIsSending(false);
    }
  };

  return (
    <aside className="flex w-full shrink-0 flex-col overflow-hidden rounded-3xl border border-[var(--stroke)] bg-white shadow-[var(--shadow)] lg:sticky lg:top-6 lg:h-[calc(100vh-3rem)] lg:w-[360px]">
      <header className="border-b border-[var(--stroke)] bg-[var(--surface-strong)] px-5 py-5">
        <p className="text-xs font-semibold uppercase tracking-[0.25em] text-[var(--gray-text)]">
          Board assistant
        </p>
        <h2 className="mt-2 font-display text-xl font-semibold text-[var(--navy-dark)]">
          Ask Kanban AI
        </h2>
        <p className="mt-2 text-sm leading-5 text-[var(--gray-text)]">
          Ask questions or request card changes.
        </p>
      </header>

      <div
        className="flex min-h-[240px] flex-1 flex-col gap-3 overflow-y-auto p-5 lg:min-h-0"
        role="log"
        aria-live="polite"
        aria-label="AI conversation"
      >
        {messages.length === 0 && (
          <p className="rounded-2xl bg-[var(--surface)] p-4 text-sm leading-6 text-[var(--gray-text)]">
            Try asking for a summary of the board or request a card to be
            created, edited, or moved.
          </p>
        )}
        {messages.map((message, index) => (
          <div
            key={`${message.role}-${index}`}
            className={
              message.role === "user"
                ? "ml-8 self-end rounded-2xl bg-[var(--primary-blue)] px-4 py-3 text-sm leading-6 text-white"
                : "mr-4 self-start rounded-2xl bg-[var(--surface)] px-4 py-3 text-sm leading-6 text-[var(--navy-dark)]"
            }
          >
            <p className="mb-1 text-[10px] font-semibold uppercase tracking-[0.18em] opacity-70">
              {message.role === "user" ? "You" : "Assistant"}
            </p>
            <p className="whitespace-pre-wrap">{message.content}</p>
          </div>
        ))}
        {isSending && (
          <p className="text-sm text-[var(--gray-text)]" role="status">
            Thinking...
          </p>
        )}
      </div>

      <form onSubmit={handleSubmit} className="border-t border-[var(--stroke)] p-4">
        {errorMessage && (
          <p className="mb-3 text-sm text-red-700" role="alert">
            {errorMessage}
          </p>
        )}
        <label
          htmlFor="ai-question"
          className="mb-2 block text-xs font-semibold uppercase tracking-wide text-[var(--gray-text)]"
        >
          Message
        </label>
        <textarea
          id="ai-question"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="Ask about your board..."
          rows={3}
          disabled={isSending}
          className="w-full resize-none rounded-2xl border border-[var(--stroke)] px-4 py-3 text-sm text-[var(--navy-dark)] outline-none transition focus:border-[var(--primary-blue)] disabled:opacity-60"
        />
        <button
          type="submit"
          disabled={isSending || !question.trim()}
          className="mt-3 w-full rounded-full bg-[var(--secondary-purple)] px-5 py-3 text-sm font-semibold text-white transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isSending ? "Sending..." : "Send"}
        </button>
      </form>
    </aside>
  );
};
