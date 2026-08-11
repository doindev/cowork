package dev.cowork.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface AgentDefRepository extends ListCrudRepository<AgentDef, UUID> {

    Optional<AgentDef> findByName(String name);

    List<AgentDef> findByEnabledTrue();
}
