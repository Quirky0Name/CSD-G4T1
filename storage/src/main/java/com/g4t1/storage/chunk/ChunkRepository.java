package com.g4t1.storage.chunk;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// The chunks table, through JdbcTemplate with no JPA entity: Hibernate can't map pgvector's vector type,
// and with no entity ddl-auto: validate never checks the table, whose H2 stand-in differs (docs/STORAGE.md)
@Repository
public class ChunkRepository {

    // Research Evaluation's embedding size; the vector(1536) column takes nothing else
    public static final int DIMENSIONS = 1536;

    private final JdbcTemplate jdbc;
    private volatile Boolean postgres;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // the paper the source belongs to; empty when there's no such source
    public Optional<UUID> paperOf(SourceType type, long sourceId) {
        String sql = switch (type) {
            case ALERT -> "select paper_id from alerts where id = ?";
            case REPORT -> "select paper_id from reports where id = ?";
            case DOCUMENT -> "select r.paper_id from report_documents d join reports r on r.id = d.report_id where d.id = ?";
            case PROFILE -> throw new IllegalArgumentException("A profile has no paper");
        };
        return jdbc.queryForList(sql, UUID.class, sourceId).stream().findFirst();
    }

    // sourceId is a Long, or a UUID for a profile; paperId is null for a profile
    public void replace(SourceType type, Object sourceId, UUID paperId, String embedModel,
                        List<StoreChunksRequest.Chunk> chunks) {
        String column = sourceColumn(type);
        jdbc.update("delete from chunks where " + column + " = ?", sourceId);
        String sql = "insert into chunks (source_type, " + column + ", paper_id, chunk_index, content, page, embedding,"
                + " embed_model, created_at) values (?, ?, ?, ?, ?, ?, " + vectorParameter() + ", ?, ?)";
        Timestamp now = Timestamp.from(Instant.now());
        for (int i = 0; i < chunks.size(); i++) {
            StoreChunksRequest.Chunk chunk = chunks.get(i);
            jdbc.update(sql, type.value(), sourceId, paperId, i, chunk.content(), chunk.page(),
                    vector(chunk.embedding()), embedModel, now);
        }
    }

    public void deleteProfile(UUID userId) {
        jdbc.update("delete from chunks where user_id = ?", userId);
    }

    // pgvector reads a vector from its text form; H2's real array takes a Java array
    String vectorParameter() {
        return isPostgres() ? "cast(? as vector)" : "?";
    }

    Object vector(List<Double> values) {
        if (isPostgres()) {
            return values.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        }
        return values.toArray(Double[]::new);
    }

    private boolean isPostgres() {
        if (postgres == null) {
            postgres = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                    "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()));
        }
        return postgres;
    }

    private static String sourceColumn(SourceType type) {
        return switch (type) {
            case ALERT -> "alert_id";
            case REPORT -> "report_id";
            case DOCUMENT -> "document_id";
            case PROFILE -> "user_id";
        };
    }
}
