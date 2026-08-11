package dev.cowork.message;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the leading recipient list of a message: {@code @name} or {@code @a,@b,@c ...}.
 * Only a prefix run of mentions addresses recipients; an empty result means broadcast.
 */
public final class MentionParser {

    private static final Pattern PREFIX = Pattern.compile("^\\s*@([A-Za-z0-9_-]+)(\\s*,\\s*@([A-Za-z0-9_-]+))*");
    private static final Pattern NAME = Pattern.compile("@([A-Za-z0-9_-]+)");

    private MentionParser() {
    }

    public static List<String> parse(String content) {
        if (content == null) {
            return List.of();
        }
        Matcher prefix = PREFIX.matcher(content);
        if (!prefix.find()) {
            return List.of();
        }
        List<String> mentions = new ArrayList<>();
        Matcher names = NAME.matcher(prefix.group());
        while (names.find()) {
            String name = names.group(1);
            if (!mentions.contains(name)) {
                mentions.add(name);
            }
        }
        return mentions;
    }
}
