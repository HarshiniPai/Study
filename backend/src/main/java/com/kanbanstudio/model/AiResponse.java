package com.kanbanstudio.model;

import java.util.List;

public record AiResponse(String answer, List<BoardAction> actions) {}
