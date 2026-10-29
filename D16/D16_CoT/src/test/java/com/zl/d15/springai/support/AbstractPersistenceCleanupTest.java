package com.zl.d15.springai.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 持久化测试清理基类。
 *
 * <p>D15_PLUS 起测试会真实写入本机 MySQL 与 Redis。每个用例结束后统一回收：
 * 测试自建（ut_ 前缀）模板的物理行、全部模板缓存 Key（含空值标记）、全部会话 Key。
 * 种子模板的数据库行不删除（由初始化器负责），缓存可随时由 MySQL 回源重建。</p>
 */
public abstract class AbstractPersistenceCleanupTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanupPersistenceArtifacts() {
        jdbcTemplate.update("DELETE FROM prompt_template WHERE template_id LIKE 'ut_%'");
        // 模板缓存全部可由 MySQL 回源重建，整前缀清理可同时覆盖空值标记与任意测试键名
        deleteByScan("d15f:prompt:tpl:*");
        deleteByScan("d15f:conv:*");
    }

    private void deleteByScan(String pattern) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
        stringRedisTemplate.execute((RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    connection.keyCommands().del(cursor.next());
                }
            }
            return null;
        });
    }
}
