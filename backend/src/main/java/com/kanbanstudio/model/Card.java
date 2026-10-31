package com.kanbanstudio.model;

import jakarta.validation.constraints.NotNull;

public record Card(@NotNull String id, @NotNull String title, @NotNull String details) {}
