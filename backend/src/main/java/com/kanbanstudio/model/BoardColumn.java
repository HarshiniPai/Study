package com.kanbanstudio.model;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BoardColumn(
        @NotNull String id,
        @NotNull String title,
        @NotNull List<@NotNull String> cardIds) {}
