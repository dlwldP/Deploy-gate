package deploygate.policy;

import deploygate.entity.ApprovalLevel;
import org.springframework.stereotype.Component;

/** Low-risk stacks: holding the claim is the whole gate. */
@Component
public class NoApprovalPolicy extends ApprovalPolicy {

    @Override
    public ApprovalLevel getSupportedLevel() {
        return ApprovalLevel.NONE;
    }

    @Override
    public int requiredApprovals() {
        return 0;
    }
}
