package com.wikiagent.persistence;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19 去重/冲突守卫 schema 迁移断言。
 * 不依赖 Spring/Flyway：H2 内存库（MODE=MySQL）先跑 V1/V7 建基表，
 * 再直接 RunScript 执行 V19 脚本文件本身，断言列/索引就位且类型正确。
 */
class V19MigrationSchemaTest {

    private static final String JDBC_URL = "jdbc:h2:mem:v19_migration_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    @Test
    void v19AddsColumnsAndIndex() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "")) {
            runScript(conn, "db/migration/V1__init.sql");
            runScript(conn, "db/migration/V7__conflict_resolution.sql");
            runScript(conn, "db/migration/V19__dedup_conflict_guard.sql");

            // kb_document：生效日期三元组（nullable）
            assertColumn(conn, "kb_document", "effective_date", "DATE", null);
            assertColumn(conn, "kb_document", "effective_set_by", "CHARACTER VARYING", 64);
            assertColumn(conn, "kb_document", "effective_set_at", "TIMESTAMP", null);
            // kb_child_chunk：内容哈希 + 普通索引
            assertColumn(conn, "kb_child_chunk", "content_hash", "CHARACTER VARYING", 64);
            assertThat(indexExists(conn, "kb_child_chunk", "idx_child_content_hash")).isTrue();
            // conflict_resolution：来源 + 解决提示
            assertColumn(conn, "conflict_resolution", "source", "CHARACTER VARYING", 32);
            assertColumn(conn, "conflict_resolution", "resolution_hint", "CHARACTER VARYING", 16);
        }
    }

    private void runScript(Connection conn, String classpathPath) throws Exception {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathPath);
        assertThat(in).as("classpath resource %s must exist", classpathPath).isNotNull();
        RunScript.execute(conn, new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private void assertColumn(Connection conn, String table, String column,
                              String expectedType, Integer expectedLength) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                select data_type, character_maximum_length from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = ? and column_name = ?
                """)) {
            ps.setString(1, table.toUpperCase());
            ps.setString(2, column.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("column %s.%s must exist", table, column).isTrue();
                assertThat(rs.getString("data_type")).isEqualTo(expectedType);
                if (expectedLength != null) {
                    assertThat(rs.getInt("character_maximum_length")).isEqualTo(expectedLength);
                }
            }
        }
    }

    private boolean indexExists(Connection conn, String table, String index) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                select index_name from information_schema.indexes
                where table_schema = 'PUBLIC' and table_name = ? and index_name = ?
                """)) {
            ps.setString(1, table.toUpperCase());
            ps.setString(2, index.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
