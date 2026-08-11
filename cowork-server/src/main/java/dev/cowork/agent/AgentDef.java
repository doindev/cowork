package dev.cowork.agent;

import java.time.Instant;
import java.util.UUID;

import dev.cowork.config.UuidAssignable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("agent_def")
public class AgentDef implements UuidAssignable {

    @Id
    private UUID id;
    private String name;
    private CliType cliType;
    private String model;
    private String options;
    private String persona;
    private String description;
    private String filePath;
    private boolean enabled = true;
    private Instant updatedAt;

    @Override
    public UUID getId() { return id; }

    @Override
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public CliType getCliType() { return cliType; }

    public void setCliType(CliType cliType) { this.cliType = cliType; }

    public String getModel() { return model; }

    public void setModel(String model) { this.model = model; }

    public String getOptions() { return options; }

    public void setOptions(String options) { this.options = options; }

    public String getPersona() { return persona; }

    public void setPersona(String persona) { this.persona = persona; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public String getFilePath() { return filePath; }

    public void setFilePath(String filePath) { this.filePath = filePath; }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
