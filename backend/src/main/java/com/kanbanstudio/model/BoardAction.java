package com.kanbanstudio.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One card change proposed by the AI. Unused fields are null. */
public record BoardAction(
        Type type,
        @JsonProperty("card_id") String cardId,
        @JsonProperty("column_id") String columnId,
        String title,
        String details,
        Integer position) {

    public enum Type {
        @JsonProperty("create_card") CREATE_CARD,
        @JsonProperty("edit_card") EDIT_CARD,
        @JsonProperty("move_card") MOVE_CARD
    }
}
