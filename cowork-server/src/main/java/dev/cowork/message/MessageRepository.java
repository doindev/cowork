package dev.cowork.message;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Message persistence over the Timescale hypertable. Plain SQL via JdbcClient because the
 * table has a composite (id, created_at) primary key and array columns.
 */
@Repository
public class MessageRepository {

    private static final RowMapper<Message> ROW_MAPPER = MessageRepository::mapRow;

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Message insert(UUID conversationId, UUID senderParticipantId, String senderName,
                          Message.Kind kind, String content, List<String> mentions, int round, UUID refId,
                          Double costUsd, String activity) {
        return jdbc.sql("""
                        INSERT INTO message (conversation_id, sender_participant_id, sender_name, kind, content, mentions, round, ref_id, cost_usd, activity)
                        VALUES (:conversationId, :senderParticipantId, :senderName, :kind, :content, :mentions, :round, :refId, :costUsd, :activity)
                        RETURNING *
                        """)
                .param("conversationId", conversationId)
                .param("senderParticipantId", senderParticipantId)
                .param("senderName", senderName)
                .param("kind", kind.name())
                .param("content", content)
                .param("mentions", mentions == null ? new String[0] : mentions.toArray(String[]::new))
                .param("round", round)
                .param("refId", refId)
                .param("costUsd", costUsd)
                .param("activity", activity)
                .query(ROW_MAPPER)
                .single();
    }

    /** Newest messages first; pass {@code before} for cursor pagination, null for the latest page. */
    public List<Message> findRecent(UUID conversationId, Instant before, int limit) {
        var sql = new StringBuilder("SELECT * FROM message WHERE conversation_id = :conversationId");
        if (before != null) {
            sql.append(" AND created_at < :before");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString())
                .param("conversationId", conversationId)
                .param("limit", limit);
        if (before != null) {
            spec = spec.param("before", OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC));
        }
        return spec.query(ROW_MAPPER).list();
    }

    /** Messages after the given instant, oldest first — used to build delta transcripts. */
    public List<Message> findSince(UUID conversationId, Instant after, int limit) {
        return jdbc.sql("""
                        SELECT * FROM message WHERE conversation_id = :conversationId AND created_at > :after
                        ORDER BY created_at ASC LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("after", OffsetDateTime.ofInstant(after, java.time.ZoneOffset.UTC))
                .param("limit", limit)
                .query(ROW_MAPPER)
                .list();
    }

    public int deleteByConversation(UUID conversationId) {
        return jdbc.sql("DELETE FROM message WHERE conversation_id = :conversationId")
                .param("conversationId", conversationId)
                .update();
    }

    public List<Message> search(String query, UUID conversationId, int limit) {
        var sql = new StringBuilder("""
                SELECT * FROM message
                WHERE to_tsvector('english', content) @@ plainto_tsquery('english', :query)
                """);
        if (conversationId != null) {
            sql.append(" AND conversation_id = :conversationId");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString())
                .param("query", query)
                .param("limit", limit);
        if (conversationId != null) {
            spec = spec.param("conversationId", conversationId);
        }
        return spec.query(ROW_MAPPER).list();
    }

    private static Message mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Message(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("sender_participant_id", UUID.class),
                rs.getString("sender_name"),
                Message.Kind.valueOf(rs.getString("kind")),
                rs.getString("content"),
                toList(rs.getArray("mentions")),
                rs.getInt("round"),
                rs.getObject("ref_id", UUID.class),
                rs.getObject("cost_usd", Double.class),
                rs.getString("activity"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.asList((String[]) array.getArray());
    }
}
