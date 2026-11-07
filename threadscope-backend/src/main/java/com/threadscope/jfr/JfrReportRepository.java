package com.threadscope.jfr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threadscope.model.AnalysisBundle;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Repository
@Profile("!test")
public class JfrReportRepository {

    private static final String SUMMARY_COLUMNS = """
        id, original_filename, recording_start, duration_millis, sample_count,
        thread_dump_count, dump_analysis_id, created_at, expires_at, summary, created_by
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JfrReportRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(StoredReport report, JfrDocuments.MemoryDocument memory) {
        jdbc.update("""
            INSERT INTO jfr_report (
                id, original_filename, recording_start, duration_millis, sample_count,
                thread_dump_count, dump_analysis_id, created_at, expires_at,
                summary, hotspot, threads, dump_bundle, memory, created_by
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            report.summary().id(),
            report.summary().filename(),
            timestamp(report.summary().recordingStartEpochMs()),
            report.summary().durationMillis(),
            report.summary().sampleCount(),
            report.summary().threadDumpCount(),
            report.summary().dumpAnalysisId(),
            timestamp(report.summary().createdAtEpochMs()),
            timestamp(report.summary().expiresAtEpochMs()),
            json(report.summaryDocument()),
            json(report.hotspot()),
            json(report.threads()),
            report.bundle() == null ? null : json(report.bundle()),
            memory == null ? null : gzip(memory),
            report.summary().createdBy()
        );
    }

    /** 旧版报告没有内存数据时为空。 */
    public Optional<JfrDocuments.MemoryDocument> findMemory(String id) {
        List<byte[]> rows = jdbc.query("SELECT memory FROM jfr_report WHERE id = ?", (rs, n) -> rs.getBytes(1), id);
        return rows.stream().filter(bytes -> bytes != null).findFirst().map(this::gunzip);
    }

    private byte[] gzip(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out, 1 << 16)) {
            mapper.writeValue(zip, value);
        } catch (IOException e) {
            throw new IllegalStateException("无法序列化 JFR 内存分析结果", e);
        }
        return out.toByteArray();
    }

    private JfrDocuments.MemoryDocument gunzip(byte[] bytes) {
        try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(bytes), 1 << 16)) {
            return mapper.readValue(zip, JfrDocuments.MemoryDocument.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 JFR 内存分析结果", e);
        }
    }

    public List<JfrDocuments.ReportSummary> list() {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM jfr_report ORDER BY created_at DESC", summaryMapper());
    }

    public Optional<JfrDocuments.ReportSummary> findSummary(String id) {
        List<JfrDocuments.ReportSummary> rows = jdbc.query(
            "SELECT " + SUMMARY_COLUMNS + " FROM jfr_report WHERE id = ?", summaryMapper(), id);
        return rows.stream().findFirst();
    }

    /** 不读 dump_bundle：它通常比热点和线程数据大一个量级，只在打开线程 Dump 时用 {@link #findBundle}。 */
    public Optional<StoredReport> find(String id) {
        List<StoredReport> rows = jdbc.query("""
            SELECT id, original_filename, recording_start, duration_millis, sample_count,
                   thread_dump_count, dump_analysis_id, created_at, expires_at,
                   summary, hotspot, threads, created_by
            FROM jfr_report WHERE id = ?
            """, (rs, n) -> {
            JfrDocuments.ReportSummary summary = mapSummary(rs);
            return new StoredReport(
                summary,
                read(rs.getString("summary"), JfrDocuments.Summary.class),
                read(rs.getString("hotspot"), JfrDocuments.HotspotDocument.class),
                read(rs.getString("threads"), JfrDocuments.ThreadDocument.class),
                null
            );
        }, id);
        return rows.stream().findFirst();
    }

    public Optional<AnalysisBundle> findBundle(String id) {
        List<String> rows = jdbc.queryForList("SELECT dump_bundle FROM jfr_report WHERE id = ?", String.class, id);
        return rows.stream().filter(json -> json != null).findFirst().map(json -> read(json, AnalysisBundle.class));
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM jfr_report WHERE id = ?", id);
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM jfr_report WHERE expires_at < ?", Timestamp.from(now));
    }

    private RowMapper<JfrDocuments.ReportSummary> summaryMapper() {
        return (rs, n) -> mapSummary(rs);
    }

    private JfrDocuments.ReportSummary mapSummary(ResultSet rs) throws SQLException {
        JfrDocuments.Summary summary = read(rs.getString("summary"), JfrDocuments.Summary.class);
        Timestamp recordingStart = rs.getTimestamp("recording_start");
        return new JfrDocuments.ReportSummary(
            rs.getString("id"),
            rs.getString("original_filename"),
            recordingStart == null ? null : recordingStart.toInstant().toEpochMilli(),
            rs.getLong("duration_millis"),
            rs.getLong("sample_count"),
            rs.getInt("thread_dump_count"),
            rs.getString("dump_analysis_id"),
            rs.getTimestamp("created_at").toInstant().toEpochMilli(),
            rs.getTimestamp("expires_at").toInstant().toEpochMilli(),
            summary == null ? null : summary.jvmVersion(),
            rs.getString("created_by")
        );
    }

    private PGobject json(Object value) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(mapper.writeValueAsString(value));
            return object;
        } catch (JsonProcessingException | SQLException e) {
            throw new IllegalStateException("无法序列化 JFR 解析结果", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取 JFR 解析结果", e);
        }
    }

    private static Timestamp timestamp(Long epochMs) {
        return epochMs == null ? null : Timestamp.from(Instant.ofEpochMilli(epochMs));
    }

    public record StoredReport(
        JfrDocuments.ReportSummary summary,
        JfrDocuments.Summary summaryDocument,
        JfrDocuments.HotspotDocument hotspot,
        JfrDocuments.ThreadDocument threads,
        AnalysisBundle bundle
    ) {}
}
