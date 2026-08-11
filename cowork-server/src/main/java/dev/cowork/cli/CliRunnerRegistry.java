package dev.cowork.cli;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.cowork.agent.CliType;
import org.springframework.stereotype.Component;

@Component
public class CliRunnerRegistry {

    private final Map<CliType, CliAgentRunner> runners = new EnumMap<>(CliType.class);

    public CliRunnerRegistry(List<CliAgentRunner> allRunners) {
        for (CliAgentRunner runner : allRunners) {
            runners.put(runner.type(), runner);
        }
    }

    public Optional<CliAgentRunner> forType(CliType type) {
        return Optional.ofNullable(runners.get(type));
    }
}
