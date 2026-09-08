package deploygate.entity;

public enum ApprovalRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /**
     * The approval was granted and then spent by a deploy. Only single-use stacks
     * reach this state; it exists so a spent approval can never be replayed.
     */
    CONSUMED
}
