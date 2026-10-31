import { expect, test, type Page } from "@playwright/test";
import { initialData } from "../src/lib/kanban";

const signIn = async (page: Page) => {
  await page.goto("/");
  await page.getByLabel("Username").fill("user");
  await page.getByLabel("Password").fill("password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator('[data-testid^="column-"]')).toHaveCount(5);
  const reset = await page.request.put("/api/board", { data: initialData });
  expect(reset.ok()).toBe(true);
  await page.reload();
  await expect(page.locator('[data-testid^="column-"]')).toHaveCount(5);
};

test("requires sign-in and keeps the session after reload", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByLabel("Username")).toBeVisible();
  await expect(page.locator('[data-testid^="column-"]')).toHaveCount(0);

  await page.getByLabel("Username").fill("user");
  await page.getByLabel("Password").fill("password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Kanban Studio" })).toBeVisible();
  await expect(page.locator('[data-testid^="column-"]')).toHaveCount(5);

  await page.reload();
  await expect(page.locator('[data-testid^="column-"]')).toHaveCount(5);
});

test("adds a card to a column", async ({ page }) => {
  await signIn(page);
  const firstColumn = page.locator('[data-testid^="column-"]').first();
  await firstColumn.getByRole("button", { name: /add a card/i }).click();
  await firstColumn.getByPlaceholder("Card title").fill("Playwright card");
  await firstColumn.getByPlaceholder("Details").fill("Added via e2e.");
  await firstColumn.getByRole("button", { name: /add card/i }).click();
  await expect(firstColumn.getByText("Playwright card")).toBeVisible();
  await page.reload();
  await expect(
    page.getByTestId(/column-col-backlog/i).getByText("Playwright card")
  ).toBeVisible();
});

test("edits and persists a card", async ({ page }) => {
  await signIn(page);
  const card = page.getByTestId("card-card-1");
  await card.getByRole("button", { name: "Edit Align roadmap themes" }).click();
  await card.getByLabel("Edit card title").fill("Updated roadmap");
  await card.getByLabel("Edit card details").fill("Updated details.");
  await card.getByRole("button", { name: "Save changes" }).click();
  await expect(card.getByText("Updated roadmap")).toBeVisible();

  await page.reload();
  await expect(page.getByTestId("card-card-1").getByText("Updated roadmap")).toBeVisible();
  await expect(page.getByTestId("card-card-1").getByText("Updated details.")).toBeVisible();
});

test("renames and persists a column", async ({ page }) => {
  await signIn(page);
  const firstColumn = page.getByTestId("column-col-backlog");
  const title = firstColumn.getByLabel("Column title");
  await title.fill("Incoming");
  await title.press("Enter");
  await expect(title).toHaveValue("Incoming");

  await page.reload();
  await expect(
    page.getByTestId("column-col-backlog").getByLabel("Column title")
  ).toHaveValue("Incoming");
});

test("shows AI chat and refreshes the board when the assistant updates it", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  const updatedBoard = structuredClone(initialData);
  updatedBoard.cards["card-1"].title = "AI updated roadmap";
  let boardResponse = initialData;

  await page.route("**/api/board", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({ json: boardResponse });
      return;
    }
    await route.continue();
  });
  await page.route("**/api/ai/chat", async (route) => {
    boardResponse = updatedBoard;
    await route.fulfill({
      json: {
        answer: "I updated the roadmap card.",
        board: updatedBoard,
        board_updated: true,
      },
    });
  });

  await page.getByLabel("Message").fill("Update the roadmap card.");
  await page.getByRole("button", { name: "Send" }).click();

  await expect(page.getByText("I updated the roadmap card.")).toBeVisible();
  await expect(page.getByText("AI updated roadmap")).toBeVisible();
});

test("moves a card between columns", async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 });
  await signIn(page);
  const card = page.getByTestId("card-card-1");
  const targetColumn = page.getByTestId("column-col-review");
  const cardBox = await card.boundingBox();
  const columnBox = await targetColumn.boundingBox();
  if (!cardBox || !columnBox) {
    throw new Error("Unable to resolve drag coordinates.");
  }

  await page.mouse.move(
    cardBox.x + cardBox.width / 2,
    cardBox.y + cardBox.height / 2
  );
  await page.mouse.down();
  await page.mouse.move(
    columnBox.x + columnBox.width / 2,
    columnBox.y + 120,
    { steps: 12 }
  );
  await page.mouse.up();
  await expect(targetColumn.getByTestId("card-card-1")).toBeVisible();
  await page.reload();
  await expect(
    page.getByTestId("column-col-review").getByTestId("card-card-1")
  ).toBeVisible();
});

test("logs out and hides the board", async ({ page }) => {
  await signIn(page);
  await page.getByRole("button", { name: "Log out" }).click();

  await expect(page.getByLabel("Username")).toBeVisible();
  await expect(page.locator('[data-testid^="column-"]')).toHaveCount(0);
});
