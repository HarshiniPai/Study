package com.kanbanstudio.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ChatMessage(@NotNull @Pattern(regexp = "user|assistant") String role, @NotNull String content) {}
