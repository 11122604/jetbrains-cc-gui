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

    /**
     * Index format version. Bump whenever the extraction logic changes the values
     * stored for a snippet — the per-file mtime+size check cannot detect that, so
     * without this the index would keep serving rows the webview can never match.
     */
    private static final int SCHEMA_VERSION = 8;

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
            int currentVersion = readSchemaVersion(st);
            // 版本升级（如新增 cwd 列）时旧表结构不兼容：CREATE TABLE IF NOT EXISTS 不会给已
            // 存在的表补列，DELETE 行后插入仍会报 "no such column"。必须整表 DROP 重建，
            // 由下次构建全量重建索引。version==0 表示全新库，无需 DROP。
            if (currentVersion != 0 && currentVersion != SCHEMA_VERSION) {
                st.execute("DROP TABLE IF EXISTS edit_snippets");
                st.execute("DROP TABLE IF EXISTS indexed_files");
                LOG.info("Edit snippet index format changed (" + currentVersion
                        + " -> " + SCHEMA_VERSION + "); dropped tables for a full rebuild");
            }

            st.execute("CREATE TABLE IF NOT EXISTS edit_snippets ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "provider TEXT NOT NULL,"
                    + "session_id TEXT NOT NULL,"
                    + "message_id TEXT NOT NULL,"
                    // Second locator candidate: the uuid the webview's merged assistant
                    // node may carry instead (see EditSnippetExtractor).
                    + "message_id_alt TEXT,"
                    + "file_path TEXT,"
                    + "snippet_type TEXT NOT NULL,"
                    + "snippet_text TEXT NOT NULL,"
                    + "snippet_hash TEXT NOT NULL,"
                    + "ts TEXT,"
                    + "cwd TEXT,"
                    + "cwd_key TEXT,"
                    // Session file's own directory: the only reliable base for reopening
                    // the session (a message's cwd may be a subdirectory of the project).
                    + "project_dir TEXT,"
                    + "project_root TEXT,"
                    + "UNIQUE(session_id, message_id, file_path, snippet_type, snippet_hash))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_snippet_text ON edit_snippets(snippet_text)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_session ON edit_snippets(session_id)");
            // 项目范围过滤（cwd 命中项目根本身或其子目录）
            st.execute("CREATE INDEX IF NOT EXISTS idx_cwd_key ON edit_snippets(cwd_key)");
            // 增量构建进度：记录已索引文件的 mtime+size，未变化则跳过重扫
            st.execute("CREATE TABLE IF NOT EXISTS indexed_files ("
                    + "path TEXT PRIMARY KEY,"
                    + "mtime INTEGER,"
                    + "size INTEGER)");

            if (currentVersion != SCHEMA_VERSION) {
                st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
        }
    }

    private static int readSchemaVersion(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
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
                + "(provider, session_id, message_id, message_id_alt, file_path, snippet_type,"
                + " snippet_text, snippet_hash, ts, cwd, cwd_key, project_dir, project_root)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (EditSnippet s : snippets) {
                ps.setString(1, s.getProvider());
                ps.setString(2, s.getSessionId());
                ps.setString(3, s.getMessageId());
                ps.setString(4, s.getMessageIdAlt());
                ps.setString(5, s.getFilePath());
                ps.setString(6, s.getSnippetType().name());
                ps.setString(7, s.getSnippetText());
                ps.setString(8, hash(s.getSnippetText()));
                ps.setString(9, s.getTs());
                ps.setString(10, s.getCwd());
                ps.setString(11, normalizePath(s.getCwd()));
                ps.setString(12, s.getProjectDir());
                ps.setString(13, s.getProjectRoot());
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
        return search(queryLines, currentFilePath, null);
    }

    /**
     * 按查询行搜索，可限定项目根集合。
     *
     * @param projectRoots 项目根的原始路径集合；cwd 命中某根本身或其子目录才纳入。
     *                     传 null/空表示不限项目（全局搜索）。
     */
    public synchronized List<SearchHit> search(List<String> queryLines, String currentFilePath,
                                               java.util.Collection<String> projectRoots) throws SQLException {
        List<SearchHit> hits = new ArrayList<>();
        if (queryLines == null || queryLines.isEmpty()) {
            return hits;
        }
        // 选中的项目根原值（用于精确匹配 project_root）
        List<String> roots = new ArrayList<>();
        if (projectRoots != null) {
            for (String root : projectRoots) {
                if (root != null && !root.isBlank() && !roots.contains(root)) {
                    roots.add(root);
                }
            }
        }
        StringBuilder sql = new StringBuilder(
                "SELECT provider, session_id, message_id, message_id_alt, file_path,"
                        + " snippet_type, snippet_text, ts, cwd, project_dir, project_root"
                        + " FROM edit_snippets"
                        + " WHERE snippet_type IN ('NEW_STRING','WRITE_CONTENT')"
                        + " AND snippet_text LIKE ? ESCAPE '\\'");
        if (!roots.isEmpty()) {
            // Match the snippet's own project root (no cwd prefix, which would pull in sibling
            // projects whose path merely starts with the selected root). Normalize both sides
            // first: IDEA's project.getBasePath() yields forward slashes while the index stores
            // the backslash form recovered from transcripts, so a raw comparison makes the
            // default scope (the current project) match nothing at all.
            sql.append(" AND (");
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("LOWER(REPLACE(COALESCE(project_root, cwd), '\\', '/')) = ?");
            }
            sql.append(")");
        }
        Map<String, MutableHit> aggregated = new HashMap<>();
        String normalizedCurrent = normalizePath(currentFilePath);
        for (String line : queryLines) {
            String pattern = "%" + escapeLike(line) + "%";
            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, pattern);
                for (String root : roots) {
                    // 与 SQL 侧的 LOWER(REPLACE(...)) 对应：大小写与斜杠方向都不敏感
                    ps.setString(idx++, normalizePath(root));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        EditSnippet snippet = new EditSnippet(
                                rs.getString("provider"),
                                rs.getString("session_id"),
                                rs.getString("message_id"),
                                rs.getString("message_id_alt"),
                                rs.getString("cwd"),
                                new EditSnippetExtractor.FileContext(
                                        rs.getString("project_dir"), rs.getString("project_root")),
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
                            // Keep the first query line that hit: the frontend uses it to
                            // scroll to and highlight that exact line after the jump.
                            aggregated.put(key, new MutableHit(snippet, 1, sameFile, line));
                        } else {
                            existing.matchedLines++;
                        }
                    }
                }
            }
        }
        for (MutableHit m : aggregated.values()) {
            hits.add(new SearchHit(m.snippet, m.matchedLines, m.sameFile, m.matchText));
        }
        hits.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        return hits;
    }

    /**
     * 列出索引中出现过的项目根（原始路径），供「选择项目」多选。
     *
     * <p>以 {@code project_root} 为准（由转录文件所在目录反解出的真实路径），缺失时退回
     * {@code cwd}；同一路径保留较短的原始写法。
     *
     * <p>不按路径前缀合并「有祖先的项」：{@code D:\projects} 与
     * {@code D:\projects\turn-right-worker} 各自拥有对应的 Claude 项目目录
     * （{@code D--projects} 与 {@code D--projects-turn-right-worker}），是两个独立项目。
     * 前者是后者的字符串前缀，并不代表后者是它的子目录 —— 旧的祖先过滤正是因此把
     * {@code turn-right-worker} 从列表里抹掉，造成不同项目下看到的可选范围不一致。
     */
    public synchronized List<String> listProjectRoots() throws SQLException {
        // 归一化 key -> 一个原始展示路径（优先较短的写法）
        java.util.LinkedHashMap<String, String> canonical = new java.util.LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT COALESCE(project_root, cwd) AS root FROM edit_snippets"
                             + " WHERE COALESCE(project_root, cwd) IS NOT NULL"
                             + " AND COALESCE(project_root, cwd) <> ''")) {
            while (rs.next()) {
                String raw = rs.getString(1);
                String key = normalizePath(raw);
                String existing = canonical.get(key);
                if (existing == null || raw.length() < existing.length()) {
                    canonical.put(key, raw);
                }
            }
        }
        List<String> roots = new ArrayList<>(canonical.values());
        roots.sort(String.CASE_INSENSITIVE_ORDER);
        return roots;
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
        private final String matchText;

        private MutableHit(EditSnippet snippet, int matchedLines, boolean sameFile, String matchText) {
            this.snippet = snippet;
            this.matchedLines = matchedLines;
            this.sameFile = sameFile;
            this.matchText = matchText;
        }
    }
}
