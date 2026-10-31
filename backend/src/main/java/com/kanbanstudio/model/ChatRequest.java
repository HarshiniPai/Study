package com.kanbanstudio.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequest(@NotNull @Size(min = 1) String question, List<@NotNull @Valid ChatMessage> history) {

    public ChatRequest {
        history = history == null ? List.of() : history;
    }
}
