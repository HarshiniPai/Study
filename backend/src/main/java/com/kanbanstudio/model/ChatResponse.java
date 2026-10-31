package com.kanbanstudio.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatResponse(String answer, BoardData board, @JsonProperty("board_updated") boolean boardUpdated) {}
