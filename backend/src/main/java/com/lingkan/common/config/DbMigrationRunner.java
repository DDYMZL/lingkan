package com.lingkan.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 数据库结构迁移执行器。
 *
 * <p>为什么不用 Flyway/Liquibase：openGauss 通过 PostgreSQL 协议对外服务，但版本号
 * 与部分系统函数与原生 PostgreSQL 存在差异，第三方迁移框架的方言适配存在不确定性。
 * 本项目第一版采用轻量自研执行器，行为显式可控：
 * <ol>
 *   <li>脚本位于 classpath:db/migration，命名 V{序号}__{说明}.sql；</li>
 *   <li>按序号升序执行，已执行脚本记录 SHA-256；</li>
 *   <li>脚本内容变更会直接报错，避免已上线库结构与脚本静默不一致；</li>
 *   <li>只新增脚本，不修改历史脚本（历史数据可追溯原则）。</li>
 * </ol>
 */
@Component
public class DbMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbMigrationRunner.class);
    private static final String LOCATION_PATTERN = "classpath:db/migration/*.sql";
    private static final String VERSION_TABLE = "schema_version";

    private final DataSource dataSource;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Value("${lingkan.db.migration.enabled:true}")
    private boolean enabled;

    @Value("${lingkan.db.migration.fail-fast:true}")
    private boolean failFast;

    public DbMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("数据库迁移已关闭（lingkan.db.migration.enabled=false）");
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureVersionTable(connection);
            List<Resource> scripts = loadScripts();
            int applied = 0;
            for (Resource script : scripts) {
                String name = script.getFilename();
                String content = read(script);
                String checksum = sha256(content);
                String recorded = findChecksum(connection, name);
                if (recorded != null) {
                    if (!recorded.equals(checksum)) {
                        throw new IllegalStateException(
                                "迁移脚本 " + name + " 在执行后被修改，请新增脚本而不是修改历史脚本");
                    }
                    continue;
                }
                executeScript(connection, name, content, checksum);
                applied++;
                log.info("已执行迁移脚本: {}", name);
            }
            log.info("数据库迁移完成，本次执行 {} 个脚本，共 {} 个脚本", applied, scripts.size());
        } catch (Exception ex) {
            if (failFast) {
                throw new IllegalStateException("数据库迁移失败: " + ex.getMessage(), ex);
            }
            log.error("数据库迁移失败（fail-fast=false，服务继续启动，数据接口可能不可用）", ex);
        }
    }

    private List<Resource> loadScripts() throws Exception {
        Resource[] resources = resolver.getResources(LOCATION_PATTERN);
        List<Resource> list = new ArrayList<>(Arrays.asList(resources));
        list.sort(Comparator.comparing(r -> {
            String name = r.getFilename();
            return name == null ? "" : name;
        }));
        return list;
    }

    private void ensureVersionTable(Connection connection) throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS schema_version (
                    id BIGSERIAL PRIMARY KEY,
                    script_name VARCHAR(200) NOT NULL,
                    checksum VARCHAR(64) NOT NULL,
                    applied_at TIMESTAMP NOT NULL,
                    CONSTRAINT uk_schema_version_name UNIQUE (script_name)
                )
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private String findChecksum(Connection connection, String scriptName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT checksum FROM " + VERSION_TABLE + " WHERE script_name = ?")) {
            ps.setString(1, scriptName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void executeScript(Connection connection, String name, String content, String checksum)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String sql : splitStatements(content)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + VERSION_TABLE
                            + " (script_name, checksum, applied_at) VALUES (?, ?, ?)")) {
                ps.setString(1, name);
                ps.setString(2, checksum);
                ps.setObject(3, LocalDateTime.now());
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw new SQLException("执行迁移脚本失败: " + name + " -> " + ex.getMessage(), ex);
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * 极简 SQL 切分：忽略行注释与字符串常量中的分号。脚本内不使用存储过程，故不处理 $$ 块之外的高级语法。
     */
    static List<String> splitStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (!inSingleQuote && c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                current.append(c);
                continue;
            }
            if (!inSingleQuote && c == ';') {
                String sql = current.toString().trim();
                if (!sql.isEmpty()) {
                    statements.add(sql);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private String read(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String sha256(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
