package dev.cowork.project;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface ProjectTaskRepository extends ListCrudRepository<ProjectTask, UUID> {

    List<ProjectTask> findByProjectIdOrderByOrdinal(UUID projectId);
}
