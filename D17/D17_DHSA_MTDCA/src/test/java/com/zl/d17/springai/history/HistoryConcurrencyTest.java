package com.zl.d17.springai.history;

import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同会话并发顺序测试（T11/T12）：多线程并发写入同一会话，
 * 验证会话父行 X 锁（chat_session 行的 SELECT ... FOR UPDATE）串行化取号、
 * 序号无重复无空洞，且不触发 InnoDB 1213 死锁。
 */
@SpringBootTest
class HistoryConcurrencyTest extends AbstractPersistenceCleanupTest {

    private static final String OWNER = "ut_conc";
    private static final int THREADS = 8;

    @Autowired
    private ChatHistoryService historyService;

    @Test
    void concurrentTurnsOnSameSessionShouldGetUniqueGaplessSequences() throws Exception {
        // 先建立会话
        ChatHistoryService.TurnStart seed = historyService.startTurn(
                OWNER, null, "", "", 0, "种子问题");
        String sessionId = seed.session().getSessionId();
        historyService.completeTurn(seed, "种子回答", "offline-mock");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        // 收集工作线程异常，避免 ExecutorService 静默吞掉真实失败原因
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ChatHistoryService.TurnStart turn = historyService.startTurn(
                            OWNER, sessionId, "", "", 0, "并发问题" + index);
                    historyService.completeTurn(turn, "并发回答" + index, "offline-mock");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(errors).as("工作线程不应抛出异常").isEmpty();

        List<Integer> sequences = jdbcTemplate.queryForList(
                "SELECT sequence_no FROM chat_message WHERE session_id = ? ORDER BY sequence_no",
                Integer.class, sessionId);
        // 种子 2 条 + 8 个并发轮次各 2 条 = 18 条，序号 1..18 连续无重复
        assertThat(sequences).hasSize(2 + THREADS * 2);
        for (int i = 0; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isEqualTo(i + 1);
        }
    }
}
