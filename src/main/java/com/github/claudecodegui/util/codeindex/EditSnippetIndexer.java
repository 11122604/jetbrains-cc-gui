package com.github.claudecodegui.util.codeindex;

import com.intellij.openapi.diagnostic.Logger;
import com.github.claudecodegui.bridge.NodeDetector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Edit 代码片段的 SQLite 索引（G0 轻量搜索）。
 *
 * <p>数据库文件位于 {@code ~/.codemoss/edit_snippets.db}。
 * 搜索采用逐行 {@code LIKE} 匹配，同文件加权排序。单连接串行访问，
 * 索引构建跑在后台线程，搜索可并发触发。
 *
 * @author luliang
 */
public class EditSnippetIndexer implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(EditSnippetIndexer.class);

    /** 单次搜索最多处理的查询行数。 */
    public static final int MAX_QUERY_LINES = 20;

    private final Connection connection;

    public EditSnippetIndexer(Path dbPath) throws SQLException {
        loadDriver();
        Path abs = dbPath.toAbsolutePath();
        Path parent = abs.getParent();
        if (parent != null) {
            parent.toFile().mkdirs();
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + abs);
        initSchema();
    }

    /** 默认数据库路径：~/.codemoss/edit_snippets.db。 */
    public static Path defaultDbPath() {
        return Paths.get(NodeDetector.resolveHomeForFileOps(), ".codemoss", "edit_snippets.db");
    }

    private static void loadDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOG.error("sqlite-jdbc driver not on classpath", e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS edit_snippets ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "provider TEXT NOT NULL,"
                    + "session_id TEXT NOT NULL,"
                    + "message_id TEXT NOT NULL,"
                    + "file_path TEXT,"
                    + "snippet_type TEXT NOT NULL,"
                    + "snippet_text TEXT NOT NULL,"
                    + "snippet_hash TEXT NOT NULL,"
                    + "ts TEXT,"
                    + "UNIQUE(session_id, message_id, file_path, snippet_type, snippet_hash))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_snippet_text ON edit_snippets(snippet_text)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_session ON edit_snippets(session_id)");
            // 增量构建进度：记录已索引文件的 mtime+size，未变化则跳过重扫
            st.execute("CREATE TABLE IF NOT EXISTS indexed_files ("
                    + "path TEXT PRIMARY KEY,"
                    + "mtime INTEGER,"
                    + "size INTEGER)");
        }
    }

    /** 文件是否已按当前 mtime+size 索引过。 */
    public synchronized boolean isFileIndexed(String path, long mtime, long size) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM indexed_files WHERE path = ? AND mtime = ? AND size = ?")) {
            ps.setString(1, path);
            ps.setLong(2, mtime);
            ps.setLong(3, size);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 标记文件已索引。 */
    public synchronized void markFileIndexed(String path, long mtime, long size) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO indexed_files(path, mtime, size) VALUES (?,?,?)")) {
            ps.setString(1, path);
            ps.setLong(2, mtime);
            ps.setLong(3, size);
            ps.executeUpdate();
        }
    }

    /** 批量插入片段（UNIQUE 约束去重：同一 session+message+file+type+hash 已存在则跳过）。 */
    public synchronized void insertAll(List<EditSnippet> snippets) throws SQLException {
        if (snippets == null || snippets.isEmpty()) {
            return;
        }
        String sql = "INSERT OR IGNORE INTO edit_snippets"
                + "(provider, session_id, message_id, file_path, snippet_type, snippet_text, snippet_hash, ts)"
                + " VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (EditSnippet s : snippets) {
                ps.setString(1, s.getProvider());
                ps.setString(2, s.getSessionId());
                ps.setString(3, s.getMessageId());
                ps.setString(4, s.getFilePath());
                ps.setString(5, s.getSnippetType().name());
                ps.setString(6, s.getSnippetText());
                ps.setString(7, hash(s.getSnippetText()));
                ps.setString(8, s.getTs());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    /** 删除某会话的全部索引记录（会话删除时同步）。 */
    public synchronized void deleteSession(String sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM edit_snippets WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    /** 当前索引条目数。 */
    public synchronized int count() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM edit_snippets")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * 按查询行搜索。逐行 LIKE 匹配，聚合结果按命中行数 + 同文件加权排序。
     *
     * @param queryLines      已归一化的查询行（trim、去空行、去重）
     * @param currentFilePath 当前编辑文件路径（用于加权），可为 null
     */
    public synchronized List<SearchHit> search(List<String> queryLines, String currentFilePath) throws SQLException {
        List<SearchHit> hits = new ArrayList<>();
        if (queryLines == null || queryLines.isEmpty()) {
            return hits;
        }
        Map<String, MutableHit> aggregated = new HashMap<>();
        String normalizedCurrent = normalizePath(currentFilePath);
        for (String line : queryLines) {
            String sql = "SELECT provider, session_id, message_id, file_path, snippet_type, snippet_text, ts"
                    + " FROM edit_snippets"
                    + " WHERE snippet_type IN ('NEW_STRING','WRITE_CONTENT')"
                    + " AND snippet_text LIKE ? ESCAPE '\\'";
            String pattern = "%" + escapeLike(line) + "%";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        EditSnippet snippet = new EditSnippet(
                                rs.getString("provider"),
                                rs.getString("session_id"),
                                rs.getString("message_id"),
                                rs.getString("file_path"),
                                EditSnippetType.valueOf(rs.getString("snippet_type")),
                                rs.getString("snippet_text"),
                                rs.getString("ts"));
                        String key = snippet.getSessionId() + "|" + snippet.getMessageId()
                                + "|" + snippet.getFilePath() + "|" + snippet.getSnippetType();
                        MutableHit existing = aggregated.get(key);
                        if (existing == null) {
                            boolean sameFile = snippet.getFilePath() != null
                                    && normalizePath(snippet.getFilePath()).equals(normalizedCurrent);
                            aggregated.put(key, new MutableHit(snippet, 1, sameFile));
                        } else {
                            existing.matchedLines++;
                        }
                    }
                }
            }
        }
        for (MutableHit m : aggregated.values()) {
            hits.add(new SearchHit(m.snippet, m.matchedLines, m.sameFile));
        }
        hits.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        return hits;
    }

    /** 归一化代码行：拆行、trim、去空行、去重、限制行数。 */
    public static List<String> normalizeLines(String code) {
        List<String> result = new ArrayList<>();
        if (code == null || code.trim().isEmpty()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : code.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || !seen.add(line)) {
                continue;
            }
            result.add(line);
            if (result.size() >= MAX_QUERY_LINES) {
                break;
            }
        }
        return result;
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').toLowerCase();
    }

    /** LIKE 通配符转义。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warn("Failed to close edit_snippets indexer", e);
        }
    }

    private static final class MutableHit {
        private final EditSnippet snippet;
        private int matchedLines;
        private final boolean sameFile;

        private MutableHit(EditSnippet snippet, int matchedLines, boolean sameFile) {
            this.snippet = snippet;
            this.matchedLines = matchedLines;
            this.sameFile = sameFile;
        }
    }
}
