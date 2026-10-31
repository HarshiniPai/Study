"use client";

import { useEffect, useState, type FormEvent } from "react";
import { AIChatSidebar } from "@/components/AIChatSidebar";
import { KanbanBoard } from "@/components/KanbanBoard";

type AuthState = "checking" | "signed-in" | "signed-out" | "error";

export const AuthGate = () => {
  const [authState, setAuthState] = useState<AuthState>("checking");
  const [errorMessage, setErrorMessage] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [boardRevision, setBoardRevision] = useState(0);

  useEffect(() => {
    let isMounted = true;

    const checkSession = async () => {
      try {
        const response = await fetch("/api/auth/me");
        if (!response.ok) {
          throw new Error("Unable to check sign-in status.");
        }
        const session: unknown = await response.json();
        if (
          typeof session !== "object" ||
          session === null ||
          !("authenticated" in session) ||
          typeof session.authenticated !== "boolean"
        ) {
          throw new Error("The sign-in service returned an invalid response.");
        }
        if (isMounted) {
          setAuthState(session.authenticated ? "signed-in" : "signed-out");
        }
      } catch {
        if (isMounted) {
          setErrorMessage("Unable to reach the sign-in service. Try again.");
          setAuthState("error");
        }
      }
    };

    void checkSession();
    return () => {
      isMounted = false;
    };
  }, []);

  const handleLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setErrorMessage("");
    setIsSubmitting(true);

    const formData = new FormData(event.currentTarget);
    const username = String(formData.get("username") ?? "");
    const password = String(formData.get("password") ?? "");

    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
      });

      if (response.status === 401) {
        setErrorMessage("Invalid username or password.");
        return;
      }
      if (!response.ok) {
        setErrorMessage("Sign-in failed. Try again.");
        return;
      }
      setAuthState("signed-in");
    } catch {
      setErrorMessage("Unable to reach the sign-in service. Try again.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleLogout = async () => {
    setErrorMessage("");
    try {
      const response = await fetch("/api/auth/logout", { method: "POST" });
      if (!response.ok) {
        throw new Error("Sign-out failed. Try again.");
      }
      setAuthState("signed-out");
    } catch (error) {
      setErrorMessage(
        error instanceof Error
          ? error.message
          : "Unable to reach the sign-in service. Try again."
      );
    }
  };

  if (authState === "checking") {
    return (
      <main className="flex min-h-screen items-center justify-center">
        <div className="text-center">
          <h1 className="font-display text-3xl font-semibold text-[var(--navy-dark)]">
            Kanban Studio
          </h1>
          <p className="mt-3 text-sm text-[var(--gray-text)]" role="status">
            Checking sign-in...
          </p>
        </div>
      </main>
    );
  }

  if (authState === "error") {
    return (
      <main className="flex min-h-screen items-center justify-center px-6">
        <section className="w-full max-w-md rounded-3xl border border-[var(--stroke)] bg-white p-8 shadow-[var(--shadow)]">
          <h1 className="font-display text-3xl font-semibold text-[var(--navy-dark)]">
            Kanban Studio
          </h1>
          <p className="mt-4 text-sm text-[var(--gray-text)]" role="alert">
            {errorMessage}
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="mt-6 rounded-full bg-[var(--secondary-purple)] px-5 py-3 text-sm font-semibold text-white"
          >
            Retry
          </button>
        </section>
      </main>
    );
  }

  if (authState === "signed-out") {
    return (
      <main className="flex min-h-screen items-center justify-center px-6 py-12">
        <section className="w-full max-w-md rounded-3xl border border-[var(--stroke)] bg-white p-8 shadow-[var(--shadow)]">
          <p className="text-xs font-semibold uppercase tracking-[0.3em] text-[var(--gray-text)]">
            Project workspace
          </p>
          <h1 className="mt-3 font-display text-3xl font-semibold text-[var(--navy-dark)]">
            Kanban Studio
          </h1>
          <p className="mt-3 text-sm text-[var(--gray-text)]">
            Sign in to open your board.
          </p>
          <form onSubmit={handleLogin} className="mt-8 space-y-5">
            <label className="block text-sm font-medium text-[var(--navy-dark)]">
              Username
              <input
                name="username"
                autoComplete="username"
                required
                className="mt-2 w-full rounded-xl border border-[var(--stroke)] px-4 py-3 outline-none focus:border-[var(--primary-blue)]"
              />
            </label>
            <label className="block text-sm font-medium text-[var(--navy-dark)]">
              Password
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                required
                className="mt-2 w-full rounded-xl border border-[var(--stroke)] px-4 py-3 outline-none focus:border-[var(--primary-blue)]"
              />
            </label>
            {errorMessage && (
              <p className="text-sm text-red-700" role="alert">
                {errorMessage}
              </p>
            )}
            <button
              type="submit"
              disabled={isSubmitting}
              className="w-full rounded-full bg-[var(--secondary-purple)] px-5 py-3 text-sm font-semibold text-white transition hover:brightness-110 disabled:opacity-60"
            >
              {isSubmitting ? "Signing in..." : "Sign in"}
            </button>
          </form>
        </section>
      </main>
    );
  }

  return (
    <>
      {errorMessage && (
        <p className="px-6 pt-3 text-right text-sm text-red-700" role="alert">
          {errorMessage}
        </p>
      )}
      <div className="flex justify-end px-6 pt-4">
        <button
          type="button"
          onClick={handleLogout}
          className="rounded-full border border-[var(--stroke)] bg-white px-4 py-2 text-sm font-semibold text-[var(--navy-dark)] transition hover:border-[var(--primary-blue)]"
        >
          Log out
        </button>
      </div>
      <div className="mx-auto flex w-full max-w-[1900px] flex-col gap-6 px-4 pb-8 lg:flex-row lg:px-6">
        <div className="min-w-0 flex-1">
          <KanbanBoard refreshKey={boardRevision} />
        </div>
        <AIChatSidebar
          onBoardUpdated={() => setBoardRevision((revision) => revision + 1)}
        />
      </div>
    </>
  );
};
