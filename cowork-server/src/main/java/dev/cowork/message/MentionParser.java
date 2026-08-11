package dev.cowork.message;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects {@code @name} mentions anywhere in a message, in order of first appearance.
 * A mention must not be immediately preceded by a word character, so email addresses
 * ({@code foo@bar.com}) and similar are not treated as mentions. An empty result means
 * the message is a broadcast.
 */
public final class MentionParser {

    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_-]+)");

    private MentionParser() {
    }

    public static List<String> parse(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> mentions = new ArrayList<>();
        Matcher matcher = MENTION.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!mentions.contains(name)) {
                mentions.add(name);
            }
        }
        return mentions;
    }
}
