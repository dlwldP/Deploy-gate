package deploygate.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Deployer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "deployer_claims", joinColumns = @JoinColumn(name = "deployer_id"))
    @Column(name = "claim", nullable = false)
    @Builder.Default
    private Set<String> claims = new HashSet<>();

    /**
     * SHA-256 hex of this deployer's API token. Null means the deployer exists as a
     * subject of policy decisions but cannot authenticate (so cannot approve/reject).
     * The plaintext token is never stored.
     */
    @Column(unique = true)
    private String apiTokenHash;
}
