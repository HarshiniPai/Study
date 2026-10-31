package com.kanbanstudio.ai;

import com.kanbanstudio.model.BoardAction;
import com.kanbanstudio.model.BoardColumn;
import com.kanbanstudio.model.BoardData;
import com.kanbanstudio.model.Card;
import java.util.List;
import java.util.UUID;

/** Applies AI-proposed card actions. Columns are never added, removed, reordered, or renamed. */
public final class BoardActions {

    private BoardActions() {}

    /** Returns a copy of the board with all actions applied; throws IllegalArgumentException if any is invalid. */
    public static BoardData apply(BoardData board, List<BoardAction> actions) {
        BoardData updated = board.copy();
        for (BoardAction action : actions) {
            switch (action.type()) {
                case CREATE_CARD -> createCard(updated, action);
                case EDIT_CARD -> editCard(updated, action);
                case MOVE_CARD -> moveCard(updated, action);
            }
        }
        return updated;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static int insertPosition(Integer position, int length) {
        if (position == null) {
            return length;
        }
        if (position < 0 || position > length) {
            throw new IllegalArgumentException("AI requested a card position outside the column.");
        }
        return position;
    }

    private static BoardColumn findColumn(BoardData board, String columnId) {
        return board.columns().stream().filter(column -> column.id().equals(columnId)).findFirst().orElse(null);
    }

    private static void createCard(BoardData board, BoardAction action) {
        if (action.cardId() != null || isEmpty(action.columnId()) || isEmpty(action.title())) {
            throw new IllegalArgumentException("Create action is missing required fields.");
        }
        if (action.title().isBlank()) {
            throw new IllegalArgumentException("Created card title cannot be empty.");
        }
        BoardColumn column = findColumn(board, action.columnId());
        if (column == null) {
            throw new IllegalArgumentException("Create action references an unknown column.");
        }
        String cardId = "ai-card-" + UUID.randomUUID().toString().replace("-", "");
        int position = insertPosition(action.position(), column.cardIds().size());
        String details = isEmpty(action.details()) ? "No details yet." : action.details();
        board.cards().put(cardId, new Card(cardId, action.title().strip(), details));
        column.cardIds().add(position, cardId);
    }

    private static void editCard(BoardData board, BoardAction action) {
        if (isEmpty(action.cardId())
                || action.columnId() != null
                || action.position() != null
                || (action.title() == null && action.details() == null)) {
            throw new IllegalArgumentException("Edit action is missing required fields.");
        }
        Card card = board.cards().get(action.cardId());
        if (card == null) {
            throw new IllegalArgumentException("Edit action references an unknown card.");
        }
        if (action.title() != null && action.title().isBlank()) {
            throw new IllegalArgumentException("Edited card title cannot be empty.");
        }
        board.cards().put(card.id(), new Card(
                card.id(),
                action.title() != null ? action.title().strip() : card.title(),
                action.details() != null ? action.details() : card.details()));
    }

    private static void moveCard(BoardData board, BoardAction action) {
        if (isEmpty(action.cardId()) || isEmpty(action.columnId()) || action.title() != null || action.details() != null) {
            throw new IllegalArgumentException("Move action is missing required fields.");
        }
        BoardColumn source = board.columns().stream()
                .filter(column -> column.cardIds().contains(action.cardId()))
                .findFirst()
                .orElse(null);
        BoardColumn target = findColumn(board, action.columnId());
        if (source == null || target == null) {
            throw new IllegalArgumentException("Move action references an unknown card or column.");
        }
        source.cardIds().remove(action.cardId());
        target.cardIds().add(insertPosition(action.position(), target.cardIds().size()), action.cardId());
    }
}
