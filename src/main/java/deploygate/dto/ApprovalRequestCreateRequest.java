package deploygate.dto;

import jakarta.validation.constraints.NotBlank;

public record ApprovalRequestCreateRequest(
        @NotBlank(message = "user must not be blank") String user,
        @NotBlank(message = "stack must not be blank") String stack,
        @NotBlank(message = "action must not be blank") String action,
        /** Commit the approvers will be signing off on. Optional unless the stack policy requires it. */
        String commitSha
) {
    public ApprovalRequestCreateRequest(String user, String stack, String action) {
        this(user, stack, action, null);
    }
}
