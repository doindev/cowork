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
    void leadingMention() {
        assertEquals(List.of("architect"), MentionParser.parse("@architect what do you think?"));
    }

    @Test
    void multipleCommaSeparatedMentions() {
        assertEquals(List.of("agent1", "user"), MentionParser.parse("@agent1,@user let's go"));
        assertEquals(List.of("a", "b", "c"), MentionParser.parse("@a, @b ,@c hi"));
    }

    @Test
    void inlineMentionsAnywhereAddress() {
        String message = """
                Nothing else relaxes — aggregate metrics unchanged.

                **@user — this reframes your tier decision.** The real argument is statistical.

                @tester you have review authority over T8-T10. @investment-architect vote too.
                """;
        assertEquals(List.of("user", "tester", "investment-architect"), MentionParser.parse(message));
    }

    @Test
    void mentionAdjacentToMarkdownIsFound() {
        assertEquals(List.of("reviewer"), MentionParser.parse("I agree with **@reviewer** here"));
    }

    @Test
    void emailAddressesAreNotMentions() {
        assertTrue(MentionParser.parse("contact me at javacoder26@gmail.com please").isEmpty());
        assertEquals(List.of("tester"), MentionParser.parse("mail foo@bar.com and ping @tester"));
    }

    @Test
    void duplicatesCollapsedInFirstAppearanceOrder() {
        assertEquals(List.of("a", "b"), MentionParser.parse("@a then @b then @a again"));
    }

    @Test
    void nullAndBlankSafe() {
        assertTrue(MentionParser.parse(null).isEmpty());
        assertTrue(MentionParser.parse("").isEmpty());
    }
}
