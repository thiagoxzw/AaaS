package com.devopsaaas.agent.api;

import com.devopsaaas.agent.Conversation;
import com.devopsaaas.agent.ConversationService;
import com.devopsaaas.agent.ConversationService.Accepted;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController {

    static final int MAX_MESSAGE_CHARS = 4000;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final ConversationService conversations;

    ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    record CreateConversation(@NotNull UUID environmentId, @Size(max = 200) String title) {
    }

    record ConversationView(UUID id, UUID environmentId, String title, String status, Instant createdAt) {
    }

    record SendMessage(@NotBlank @Size(max = MAX_MESSAGE_CHARS) String content) {
    }

    record ExecutionAccepted(UUID executionId, UUID conversationId, String status, boolean replayed) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('AGENT_INTERACT')")
    ResponseEntity<ConversationView> create(@AuthenticationPrincipal CurrentUser user,
            @Valid @RequestBody CreateConversation request) {
        Conversation conversation = conversations.open(user, request.environmentId(), request.title());
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + conversation.getId()))
                .body(new ConversationView(conversation.getId(), conversation.getEnvironmentId(),
                        conversation.getTitle(), conversation.getStatus().name(), conversation.getCreatedAt()));
    }

    /** RF-22: 202 at once; the client follows the execution at /api/v1/executions/{id}. */
    @PostMapping("/{conversationId}/messages")
    @PreAuthorize("hasAuthority('AGENT_INTERACT')")
    ResponseEntity<ExecutionAccepted> send(@AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID conversationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SendMessage request) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.unprocessable(
                    "Idempotency-Key must have 8 to 128 characters: letters, digits, '.', '_', ':' or '-'.");
        }
        Accepted accepted = conversations.send(user, conversationId, request.content(), idempotencyKey);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/executions/" + accepted.executionId()))
                .body(new ExecutionAccepted(accepted.executionId(), accepted.conversationId(),
                        accepted.status().name(), accepted.replayed()));
    }
}
