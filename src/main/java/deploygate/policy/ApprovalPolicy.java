package deploygate.policy;

import deploygate.entity.ApprovalLevel;
import deploygate.entity.Deployer;
import deploygate.entity.StackPolicy;

/**
 * How one approval level decides a deploy.
 *
 * <p>Every level starts from the same question — does the deployer hold the claim the
 * stack demands — and then differs only in how many people have to sign off. That
 * shared part lives here, so a level is defined by declaring its two facts rather than
 * by repeating the claim check.
 */
public abstract class ApprovalPolicy {

    public abstract ApprovalLevel getSupportedLevel();

    /** Distinct approvers this level demands. Zero means the deploy needs no sign-off. */
    public abstract int requiredApprovals();

    /**
     * The action is part of the question, not decoration: a stack may gate destroying
     * on a different claim than deploying, and a stack that was never configured for an
     * action must not answer for it.
     */
    public ApprovalResult evaluate(Deployer deployer, StackPolicy stackPolicy, String action) {
        if (!stackPolicy.allowsAction(action)) {
            return ApprovalResult.denied("stack " + stackPolicy.getStackName()
                    + " does not allow action: " + action);
        }
        String requiredClaim = stackPolicy.requiredClaimFor(action);
        if (!deployer.getClaims().contains(requiredClaim)) {
            return ApprovalResult.denied("deployer is missing required claim: " + requiredClaim);
        }
        if (requiredApprovals() == 0) {
            return ApprovalResult.allowed("deployer has required claim: " + requiredClaim);
        }
        return ApprovalResult.pending("requires " + requiredApprovals() + " distinct approver"
                + (requiredApprovals() == 1 ? "" : "s"));
    }
}
