package dev.cowork.project;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface ProjectRepository extends ListCrudRepository<Project, UUID> {

    Optional<Project> findByName(String name);
}
