package deploygate.dao;

import deploygate.entity.Deployer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeployerRepository extends JpaRepository<Deployer, Long> {

    Optional<Deployer> findByName(String name);

    Optional<Deployer> findByApiTokenHash(String apiTokenHash);
}
