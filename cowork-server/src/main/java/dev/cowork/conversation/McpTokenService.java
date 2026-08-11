package dev.cowork.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Issues and resolves per-participant bearer tokens used by agent CLIs to authenticate
 * against the embedded MCP server. Only the SHA-256 hash is stored; a fresh token is
 * issued for each CLI turn, so plaintext never needs to survive a restart.
 */
@Service
public class McpTokenService {

    private final SecureRandom random = new SecureRandom();
    private final ParticipantRepository participants;

    public McpTokenService(ParticipantRepository participants) {
        this.participants = participants;
    }

    /** Generates a new token for the participant, stores its hash, and returns the plaintext. */
    public String issueToken(Participant participant) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        participant.setMcpTokenHash(hash(token));
        participants.save(participant);
        return token;
    }

    public Optional<Participant> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return participants.findByMcpTokenHash(hash(token));
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
