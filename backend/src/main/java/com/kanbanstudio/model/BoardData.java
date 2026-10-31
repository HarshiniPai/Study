package com.kanbanstudio.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BoardData(
        @NotNull List<@NotNull @Valid BoardColumn> columns,
        @NotNull Map<String, @NotNull @Valid Card> cards) {

    /** Returns a copy whose column card lists and card map can be modified independently. */
    public BoardData copy() {
        List<BoardColumn> copiedColumns = new ArrayList<>();
        for (BoardColumn column : columns) {
            copiedColumns.add(new BoardColumn(column.id(), column.title(), new ArrayList<>(column.cardIds())));
        }
        return new BoardData(copiedColumns, new LinkedHashMap<>(cards));
    }
}
