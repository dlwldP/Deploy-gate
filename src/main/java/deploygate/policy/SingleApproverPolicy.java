package deploygate.policy;

import deploygate.entity.ApprovalLevel;
import org.springframework.stereotype.Component;

/** One other person has to sign off before the deploy goes ahead. */
@Component
public class SingleApproverPolicy extends ApprovalPolicy {

    @Override
    public ApprovalLevel getSupportedLevel() {
        return ApprovalLevel.SINGLE_APPROVER;
    }

    @Override
    public int requiredApprovals() {
        return 1;
    }
}
