package com.zl.d17.springai.history;

import com.zl.d17.history.ChatHistoryService;
import com.zl.d17.history.mapper.ChatSessionMapper;
import com.zl.d17.springai.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 诊断：在真实 Spring 事务 + MyBatis 环境下验证会话父行 FOR UPDATE 是否阻塞，
 * 并区分裸 mapper、编程式事务与声明式 @Transactional 三条路径。
 */
@SpringBootTest
class SpringLockDiagnosticTest extends AbstractPersistenceCleanupTest {

    private static final long HOLD_MILLIS = 5000;

    @Autowired
    private ChatHistoryService historyService;

    @Autowired
    private ChatSessionMapper sessionMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void parentRowLockShouldBlockSecondThread() throws Exception {
        System.out.println("DIAG serviceClass=" + historyService.getClass().getName());
        System.out.println("DIAG mapperClass=" + sessionMapper.getClass().getName());

        ChatHistoryService.TurnStart seed = historyService.startTurn(
                "ut_diag", null, "", "", 0, "诊断种子问题");
        String sessionId = seed.session().getSessionId();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    Long id = sessionMapper.lockSessionForUpdate(sessionId);
                    System.out.println("[T1] lock acquired id=" + id);
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("[T1] releasing lock");
                });
            } finally {
                done.countDown();
            }
        });
        t1.start();

        if (!entered.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("T1 未能在限定时间内取得锁");
        }

        CountDownLatch threadsStart = new CountDownLatch(1);

        // 路径 A：无事务裸 mapper 调用（autocommit），应阻塞
        AtomicLong waitA = new AtomicLong(-1);
        Thread t2 = new Thread(() -> {
            try {
                threadsStart.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long start = System.currentTimeMillis();
            Long id = sessionMapper.lockSessionForUpdate(sessionId);
            waitA.set(System.currentTimeMillis() - start);
            System.out.println("[T2] mapper lock returned id=" + id + " wait=" + waitA.get() + "ms");
        });

        // 路径 B：声明式 @Transactional 服务方法，应阻塞
        AtomicLong waitB = new AtomicLong(-1);
        AtomicReference<Throwable> t3Error = new AtomicReference<>();
        Thread t3 = new Thread(() -> {
            try {
                threadsStart.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long start = System.currentTimeMillis();
            try {
                historyService.startTurn("ut_diag", sessionId, "", "", 0, "诊断并发问题");
            } catch (Throwable t) {
                t3Error.set(t);
            }
            waitB.set(System.currentTimeMillis() - start);
            System.out.println("[T3] declarative startTurn returned wait=" + waitB.get()
                    + "ms error=" + t3Error.get());
        });

        t2.start();
        t3.start();
        threadsStart.countDown();

        // T1 再持锁 HOLD_MILLIS 后释放，让两条路径在锁上等待
        Thread.sleep(HOLD_MILLIS);
        release.countDown();

        t2.join(15000);
        t3.join(15000);

        System.out.println("DIAG_RESULT waitA=" + waitA.get() + " blockedA=" + (waitA.get() > 1000)
                + " waitB=" + waitB.get() + " blockedB=" + (waitB.get() > 1000)
                + " t3Error=" + t3Error.get());
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new AssertionError("T1 事务未能正常结束");
        }
    }
}
