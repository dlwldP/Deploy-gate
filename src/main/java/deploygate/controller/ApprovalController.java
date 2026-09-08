package deploygate.controller;

import deploygate.dto.ApprovalActionResponse;
import deploygate.dto.ApprovalCheckRequest;
import deploygate.dto.ApprovalCheckResponse;
import deploygate.dto.ApprovalHistoryEntry;
import deploygate.dto.ApprovalRequestCreateRequest;
import deploygate.dto.ApprovalRequestResponse;
import deploygate.policy.ApprovalResult;
import deploygate.policy.Decision;
import deploygate.security.AuthenticatedCaller;
import deploygate.service.ApprovalCheckService;
import deploygate.service.ApprovalRequestService;
import deploygate.validation.DeployerNotAuthorizedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/approval")
public class ApprovalController {

    private final ApprovalCheckService approvalCheckService;
    private final ApprovalRequestService approvalRequestService;

    public ApprovalController(ApprovalCheckService approvalCheckService, ApprovalRequestService approvalRequestService) {
        this.approvalCheckService = approvalCheckService;
        this.approvalRequestService = approvalRequestService;
    }

    @PostMapping("/check")
    public ResponseEntity<ApprovalCheckResponse> check(@Valid @RequestBody ApprovalCheckRequest request,
                                                       @AuthenticationPrincipal AuthenticatedCaller caller) {
        String user = resolveSubject(caller, request.user());
        ApprovalResult result = approvalCheckService.check(user, request.stack(), request.action(),
                resolveCommitSha(caller, request.commitSha()));
        HttpStatus status = switch (result.decision()) {
            case ALLOWED -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            case DENIED -> HttpStatus.FORBIDDEN;
        };
        ApprovalCheckResponse body = new ApprovalCheckResponse(result.decision().name(), request.stack(), result.reason());
        return ResponseEntity.status(status).body(body);
    }

    @PostMapping("/request")
    public ResponseEntity<ApprovalRequestResponse> request(@Valid @RequestBody ApprovalRequestCreateRequest request,
                                                           @AuthenticationPrincipal AuthenticatedCaller caller) {
        String user = resolveSubject(caller, request.user());
        return ResponseEntity.ok(approvalRequestService.createRequest(user, request.stack(), request.action(),
                resolveCommitSha(caller, request.commitSha())));
    }

    /**
     * The approver is taken from the authenticated token, never from the request body —
     * otherwise anyone holding any token could vote as somebody else.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApprovalActionResponse> approve(@PathVariable Long id,
                                                          @AuthenticationPrincipal AuthenticatedCaller caller) {
        return ResponseEntity.ok(approvalRequestService.approve(id, caller.name()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApprovalActionResponse> reject(@PathVariable Long id,
                                                         @AuthenticationPrincipal AuthenticatedCaller caller) {
        return ResponseEntity.ok(approvalRequestService.reject(id, caller.name()));
    }

    @GetMapping("/history")
    public ResponseEntity<List<ApprovalHistoryEntry>> history(
            @RequestParam(required = false) String stack,
            @RequestParam(required = false) String user,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(approvalRequestService.history(Optional.ofNullable(stack), Optional.ofNullable(user), limit));
    }

    /**
     * Decides whose deploy permission is being evaluated. Only the shared CI service
     * token may name someone else — it is the one credential that proves no identity,
     * so it relays the name the pipeline authenticated on its own side. A personal
     * token or a GitHub-attested OIDC token may only ever ask about itself.
     */
    private String resolveSubject(AuthenticatedCaller caller, String requestedUser) {
        if (!caller.actsOnlyAsSelf()) {
            return requestedUser;
        }
        if (!caller.name().equals(requestedUser)) {
            throw new DeployerNotAuthorizedException(
                    "token belongs to '" + caller.name() + "' and cannot act on behalf of '" + requestedUser + "'");
        }
        return caller.name();
    }

    /**
     * When the credential itself attests which commit is being built, that wins: a
     * caller must not be able to get an approval bound to a commit other than the one
     * its own token vouches for.
     */
    private String resolveCommitSha(AuthenticatedCaller caller, String requestedSha) {
        String attested = caller.attestedCommitSha();
        if (attested == null || attested.isBlank()) {
            return requestedSha;
        }
        if (requestedSha != null && !requestedSha.isBlank() && !attested.equalsIgnoreCase(requestedSha.trim())) {
            throw new DeployerNotAuthorizedException("token attests commit '" + attested
                    + "' but '" + requestedSha + "' was requested");
        }
        return attested;
    }
}
