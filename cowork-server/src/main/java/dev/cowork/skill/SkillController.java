package dev.cowork.skill;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.cowork.conversation.Conversation;
import dev.cowork.conversation.ConversationService;
import dev.cowork.message.Message;
import dev.cowork.message.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SkillController {

    public record SkillView(String name, String description, List<String> phases, String requiresBinary) {

        static SkillView of(SkillDef def) {
            return new SkillView(def.name(), def.description(),
                    def.defaultPhases().stream().map(Enum::name).sorted().toList(), def.requiresBinary());
        }
    }

    public record ConversationSkillView(String name, String description, List<String> phases,
                                        String requiresBinary, boolean active, boolean overridden,
                                        boolean available) {

        static ConversationSkillView of(SkillService.EffectiveSkill effective) {
            SkillView base = SkillView.of(effective.def());
            return new ConversationSkillView(base.name(), base.description(), base.phases(),
                    base.requiresBinary(), effective.active(), effective.overridden(), effective.available());
        }
    }

    public record ToggleRequest(Boolean active) {
    }

    private final SkillService skills;
    private final ConversationService conversations;
    private final MessageService messages;

    public SkillController(SkillService skills, ConversationService conversations, MessageService messages) {
        this.skills = skills;
        this.conversations = conversations;
        this.messages = messages;
    }

    @GetMapping("/api/skills")
    public List<SkillView> list() {
        return skills.allSkills().stream().map(SkillView::of).toList();
    }

    @GetMapping("/api/conversations/{id}/skills")
    public List<ConversationSkillView> forConversation(@PathVariable UUID id) {
        Conversation conversation = conversations.get(id);
        return skills.forConversation(conversation).stream().map(ConversationSkillView::of).toList();
    }

    @PutMapping("/api/conversations/{id}/skills/{name}")
    public ConversationSkillView toggle(@PathVariable UUID id, @PathVariable String name,
                                        @RequestBody ToggleRequest request) {
        if (request.active() == null) {
            throw new IllegalArgumentException("active is required");
        }
        Conversation conversation = conversations.get(id);
        boolean wasActive = skills.forConversation(conversation).stream()
                .anyMatch(s -> s.def().name().equals(name) && s.active());
        SkillService.EffectiveSkill effective = skills.setActive(id, name, request.active());
        if (wasActive != effective.active()) {
            String suffix;
            if (!effective.active()) {
                suffix = ".";
            } else if (effective.available()) {
                suffix = " — its protocol now applies to every agent turn.";
            } else {
                suffix = " — but '" + effective.def().requiresBinary()
                        + "' is not installed, so it stays inactive until it is.";
            }
            messages.postSystem(id, Message.Kind.SYSTEM,
                    "Skill \"" + name + "\" " + (effective.active() ? "activated" : "deactivated")
                            + " by the user" + suffix, null);
        }
        return ConversationSkillView.of(effective);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("message", e.getMessage());
    }
}
