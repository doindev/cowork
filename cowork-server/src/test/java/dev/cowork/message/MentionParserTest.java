package dev.cowork.message;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionParserTest {

    @Test
    void broadcastHasNoMentions() {
        assertTrue(MentionParser.parse("hello everyone").isEmpty());
    }

    @Test
    void singleMention() {
        assertEquals(List.of("architect"), MentionParser.parse("@architect what do you think?"));
    }

    @Test
    void multipleCommaSeparatedMentions() {
        assertEquals(List.of("agent1", "user"), MentionParser.parse("@agent1,@user let's go"));
        assertEquals(List.of("a", "b", "c"), MentionParser.parse("@a, @b ,@c hi"));
    }

    @Test
    void midMessageMentionsDoNotAddress() {
        assertTrue(MentionParser.parse("I think @architect is right").isEmpty());
    }

    @Test
    void duplicatesCollapsed() {
        assertEquals(List.of("a"), MentionParser.parse("@a,@a hello"));
    }

    @Test
    void nullAndBlankSafe() {
        assertTrue(MentionParser.parse(null).isEmpty());
        assertTrue(MentionParser.parse("").isEmpty());
    }
}
