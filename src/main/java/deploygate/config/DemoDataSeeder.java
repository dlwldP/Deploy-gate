package deploygate.config;

import deploygate.dao.DeployerRepository;
import deploygate.dao.StackPolicyRepository;
import deploygate.entity.ApprovalLevel;
import deploygate.entity.Deployer;
import deploygate.entity.StackPolicy;
import deploygate.security.ApiTokens;
import deploygate.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds demo deployers, stack policies and well-known API tokens.
 *
 * <p>Disabled unless {@code deploygate.demo-data.enabled=true} is set explicitly: the
 * tokens below are published in the repository, so seeding them into a real deployment
 * would hand out valid credentials to anyone who read the source.
 */
@Component
@ConditionalOnProperty(name = "deploygate.demo-data.enabled", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    /** Demo-only tokens. Never use these outside local development or CI demos. */
    public static final String JIYE_TOKEN = "dgt_demo_jiye";
    public static final String ALICE_TOKEN = "dgt_demo_alice";
    public static final String BOB_TOKEN = "dgt_demo_bob";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DeployerRepository deployerRepository;
    private final StackPolicyRepository stackPolicyRepository;

    public DemoDataSeeder(DeployerRepository deployerRepository, StackPolicyRepository stackPolicyRepository) {
        this.deployerRepository = deployerRepository;
        this.stackPolicyRepository = stackPolicyRepository;
    }

    @Override
    public void run(String... args) {
        if (deployerRepository.count() > 0) {
            return;
        }

        log.warn("Seeding demo data with publicly known API tokens — do not enable this in a real deployment.");

        deployerRepository.save(Deployer.builder()
                .name("jiye")
                .claims(Set.of(
                        "stack:SmallAppStack:deploy",
                        "stack:SmallAppStack:destroy",
                        "stack:ProdAlbStack:deploy",
                        "stack:StagingApiStack:deploy",
                        SecurityConfig.ADMIN_READ_CLAIM))
                .apiTokenHash(ApiTokens.hash(JIYE_TOKEN))
                .build());

        deployerRepository.save(Deployer.builder()
                .name("alice")
                .claims(Set.of(
                        "stack:ProdAlbStack:approve",
                        "stack:StagingApiStack:approve",
                        SecurityConfig.ADMIN_READ_CLAIM))
                .apiTokenHash(ApiTokens.hash(ALICE_TOKEN))
                .build());

        deployerRepository.save(Deployer.builder()
                .name("bob")
                .claims(Set.of("stack:ProdAlbStack:approve"))
                .apiTokenHash(ApiTokens.hash(BOB_TOKEN))
                .build());

        stackPolicyRepository.save(StackPolicy.builder()
                .stackName("SmallAppStack")
                .requiredClaim("stack:SmallAppStack:deploy")
                .approvalLevel(ApprovalLevel.NONE)
                .build());

        stackPolicyRepository.save(StackPolicy.builder()
                .stackName("StagingApiStack")
                .requiredClaim("stack:StagingApiStack:deploy")
                .approvalLevel(ApprovalLevel.SINGLE_APPROVER)
                .approvalTtlMinutes(120)
                .build());

        // The production stack is the strict one: approval covers exactly one commit
        // and is spent by the deploy it authorises.
        stackPolicyRepository.save(StackPolicy.builder()
                .stackName("ProdAlbStack")
                .requiredClaim("stack:ProdAlbStack:deploy")
                .approvalLevel(ApprovalLevel.DUAL_APPROVER)
                .approvalTtlMinutes(30)
                .requireCommitSha(true)
                .singleUse(true)
                .build());
    }
}
