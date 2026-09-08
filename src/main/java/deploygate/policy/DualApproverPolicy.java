package deploygate.policy;

import deploygate.entity.ApprovalLevel;
import org.springframework.stereotype.Component;

/** Production stacks: two distinct people have to sign off. */
@Component
public class DualApproverPolicy extends ApprovalPolicy {

    @Override
    public ApprovalLevel getSupportedLevel() {
        return ApprovalLevel.DUAL_APPROVER;
    }

    @Override
    public int requiredApprovals() {
        return 2;
    }
}
