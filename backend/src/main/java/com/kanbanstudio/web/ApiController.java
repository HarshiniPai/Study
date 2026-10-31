package com.kanbanstudio.web;

import com.kanbanstudio.ai.AiService;
import com.kanbanstudio.ai.BoardActions;
import com.kanbanstudio.ai.OpenRouterClient.OpenRouterConfigurationException;
import com.kanbanstudio.ai.OpenRouterClient.OpenRouterException;
import com.kanbanstudio.db.BoardRepository;
import com.kanbanstudio.db.BoardRepository.BoardNotFoundException;
import com.kanbanstudio.db.BoardRepository.InvalidBoardException;
import com.kanbanstudio.model.AuthStatus;
import com.kanbanstudio.model.BoardData;
import com.kanbanstudio.model.ChatRequest;
import com.kanbanstudio.model.ChatResponse;
import com.kanbanstudio.model.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class ApiController {

    // Local MVP credentials; the database supports more users for later.
    static final String MVP_USERNAME = BoardRepository.MVP_USERNAME;
    static final String MVP_PASSWORD = "password";
    static final String SESSION_USERNAME = "username";

    private final BoardRepository boards;
    private final AiService aiService;

    ApiController(BoardRepository boards, AiService aiService) {
        this.boards = boards;
        this.aiService = aiService;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/auth/me")
    AuthStatus me(HttpServletRequest request) {
        boolean authenticated = MVP_USERNAME.equals(sessionUsername(request));
        return new AuthStatus(authenticated, authenticated ? MVP_USERNAME : null);
    }

    @PostMapping("/auth/login")
    Map<String, String> login(@Valid @RequestBody LoginRequest login, HttpServletRequest request) {
        if (!MVP_USERNAME.equals(login.username()) || !MVP_PASSWORD.equals(login.password())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true).setAttribute(SESSION_USERNAME, MVP_USERNAME);
        return Map.of("username", MVP_USERNAME);
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/board")
    BoardData readBoard(HttpServletRequest request) {
        return loadBoard(signedInUserId(request));
    }

    @PutMapping("/board")
    BoardData updateBoard(@Valid @RequestBody BoardData board, HttpServletRequest request) {
        String userId = signedInUserId(request);
        try {
            return boards.saveBoard(userId, board);
        } catch (InvalidBoardException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        } catch (BoardNotFoundException error) {
            throw new ApiException(HttpStatus.NOT_FOUND, error.getMessage(), error);
        }
    }

    @PostMapping("/ai/chat")
    ChatResponse chat(@Valid @RequestBody ChatRequest chat, HttpServletRequest request) {
        String userId = signedInUserId(request);
        if (chat.question().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question cannot be empty.");
        }
        BoardData board = loadBoard(userId);

        AiService.Reply reply;
        try {
            reply = aiService.respond(board, chat.question(), chat.history());
        } catch (OpenRouterConfigurationException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, error.getMessage(), error);
        } catch (OpenRouterException error) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, error.getMessage(), error);
        }

        // The AI call can take a while, so apply its actions to the latest saved board
        // rather than the earlier snapshot, or edits saved in the meantime would be lost.
        BoardData currentBoard = loadBoard(userId);
        BoardData updatedBoard;
        try {
            updatedBoard = BoardActions.apply(currentBoard, reply.actions());
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The board changed while the assistant was responding. Try again.", error);
        }

        boolean boardUpdated = !updatedBoard.equals(currentBoard);
        if (boardUpdated) {
            try {
                boards.saveBoard(userId, updatedBoard);
            } catch (InvalidBoardException error) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI update could not be applied to the board.", error);
            }
        }
        return new ChatResponse(reply.answer(), updatedBoard, boardUpdated);
    }

    private static String sessionUsername(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(SESSION_USERNAME);
    }

    private String signedInUserId(HttpServletRequest request) {
        String username = sessionUsername(request);
        if (!MVP_USERNAME.equals(username)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in required.");
        }
        return boards.findUserId(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Signed-in user not found."));
    }

    private BoardData loadBoard(String userId) {
        return boards.getBoard(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Board not found."));
    }
}
