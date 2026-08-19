package dev.cowork.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.Participant;
import dev.cowork.rtk.RtkToolSupport;
import dev.cowork.skill.SkillDef;
import dev.cowork.skill.SkillService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranscriptBuilderSkillTest {

    private static Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(java.util.UUID.randomUUID());
        conversation.setTitle("test");
        conversation.setCreatedAt(Instant.now());
        return conversation;
    }

    private static Participant agent(String name) {
        Participant participant = new Participant();
        participant.setId(java.util.UUID.randomUUID());
        participant.setKind(Participant.Kind.AGENT);
        participant.setDisplayName(name);
        return participant;
    }

    private String promptWithSkillBody(String body, RtkToolSupport tools) {
        SkillService skills = mock(SkillService.class);
        when(skills.activeFor(any())).thenReturn(List.of(
                new SkillDef("rtk", "desc", Set.of(), "rtk", body)));
        Participant agent = agent("coder");
        return new TranscriptBuilder(skills, tools).build(conversation(), agent, List.of(agent),
                List.of(), 0, false, null, false, false, false);
    }

    @Test
    void replacesRtkPlaceholderWithTheMachinesUnwrappableTools() {
        RtkToolSupport tools = mock(RtkToolSupport.class);
        when(tools.promptNote()).thenReturn(
                "- NOT AVAILABLE on this machine — run these plain, never via rtk: cat, grep.\n");

        String prompt = promptWithSkillBody("Use rtk.\n{{RTK_UNAVAILABLE}}- Otherwise wrap it.\n", tools);

        assertFalse(prompt.contains("{{RTK_UNAVAILABLE}}"), "placeholder must not reach the agent");
        assertTrue(prompt.contains("never via rtk: cat, grep"));
    }

    @Test
    void skillBodyWithoutPlaceholderIsInjectedVerbatim() {
        RtkToolSupport tools = mock(RtkToolSupport.class);
        when(tools.promptNote()).thenReturn("");

        String prompt = promptWithSkillBody("Plain protocol text.\n", tools);

        assertTrue(prompt.contains("[ACTIVE SKILL: rtk]"));
        assertTrue(prompt.contains("Plain protocol text."));
    }
}
