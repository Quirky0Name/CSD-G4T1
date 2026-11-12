package com.g4t1.storage.chunk;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        for (int i = 0; i < chunks.size(); i++) {
            StoreChunksRequest.Chunk chunk = chunks.get(i);
            jdbc.update(sql, type.value(), sourceId, paperId, i, chunk.content(), chunk.page(),
                    vector(chunk.embedding()), embedModel, now);
        }
    }

    // The paper's chunks of one source type (the owner's, for a profile), nearest first by cosine
    // distance. Postgres only: H2 has no vector distance. Runs inside a transaction, for set local.
    public List<ChunkMatch> search(UUID paperId, SourceType type, List<Double> embedding, int limit) {
        // the HNSW index with a where filter otherwise stops at ef_search candidates and can return fewer
        // than limit; iterative scan keeps going until the filter has found enough, still nearest first
        jdbc.execute("set local hnsw.iterative_scan = strict_order");
        String scope = type == SourceType.PROFILE
                ? "c.source_type = 'profile' and c.user_id = (select owner_id from papers where id = ?)"
                : "c.paper_id = ? and c.source_type = ?";
        String sql = """
                select c.source_type, c.alert_id, c.report_id, c.document_id, c.user_id, c.chunk_index, c.page,
                       c.content,
                       case c.source_type when 'alert' then a.report_id when 'report' then c.report_id
                                          when 'document' then d.report_id end as source_report_id,
                       case c.source_type when 'alert' then a.detected_at when 'report' then r.evaluated_at
                                          when 'document' then d.created_at else c.created_at end as source_date,
                       1 - (c.embedding <=> cast(? as vector)) as score
                from chunks c
                left join alerts a on a.id = c.alert_id
                left join reports r on r.id = c.report_id
                left join report_documents d on d.id = c.document_id
                where %s
                order by c.embedding <=> cast(? as vector)
                limit ?""".formatted(scope);
        String vector = (String) vector(embedding);
        Object[] args = type == SourceType.PROFILE
                ? new Object[]{vector, paperId, vector, limit}
                : new Object[]{vector, paperId, type.value(), vector, limit};
        return jdbc.query(sql, (rs, row) -> {
            SourceType source = SourceType.of(rs.getString("source_type"));
            Object sourceId = rs.getObject(sourceColumn(source));
            long reportId = rs.getLong("source_report_id");
            Long report = rs.wasNull() ? null : reportId;
            Timestamp date = rs.getTimestamp("source_date");
            return new ChunkMatch(source, sourceId.toString(), rs.getInt("chunk_index"),
                    (Integer) rs.getObject("page"), report, date == null ? null : date.toInstant(),
                    rs.getString("content"), rs.getDouble("score"));
        }, args);
    }

    public boolean paperExists(UUID paperId) {
        return !jdbc.queryForList("select 1 from papers where id = ?", Integer.class, paperId).isEmpty();
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
