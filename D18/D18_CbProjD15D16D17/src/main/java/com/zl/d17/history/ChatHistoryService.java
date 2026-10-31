package com.zl.d17.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zl.d17.history.config.HistoryProperties;
import com.zl.d17.history.mapper.ChatSessionMapper;
import com.zl.d17.history.mapper.HistoryMessageMapper;
import com.zl.d17.history.model.ChatSession;
import com.zl.d17.history.model.HistoryMessage;
import com.zl.d17.history.model.MessageStatus;
import com.zl.d17.history.model.SessionStatus;
import com.zl.d17.history.po.ChatSessionPO;
import com.zl.d17.history.po.HistoryMessagePO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史核心服务：负责会话生命周期、消息状态机、上下文窗口选取与历史分页查询。
 *
 * <p>设计要点：</p>
 * <ul>
 *     <li>写入路径只此一条，不引入 Spring AI ChatMemory/Advisor，从结构上避免双写；</li>
 *     <li>每个状态变更都是独立短事务，模型 HTTP 调用在事务外，不持有数据库连接等待外部响应；</li>
 *     <li>会话内序号分配先对 chat_session 父行加排他记录锁（父行 X 锁），同会话写事务被
 *     完全串行化后再以普通 SELECT 取最大序号加一，唯一约束 uk_session_sequence 兜底；
 *     不使用消息表 {@code SELECT MAX ... FOR UPDATE}，避免间隙锁与插入意向锁互斥导致的
 *     InnoDB 1213 死锁；</li>
 *     <li>父行锁必须是写事务内的第一条语句、先于任何普通 SELECT：REPEATABLE READ
 *     （可重复读）隔离级别下首次一致性读会建立读视图，若先读后锁，锁等待结束后取号
 *     仍走旧视图而读不到并发事务已提交的序号，会触发 uk_session_sequence 重复键；</li>
 *     <li>上下文窗口只包含 COMPLETED 消息，PENDING/FAILED/INTERRUPTED 不进入模型请求。</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatHistoryService {

    /** 用户消息角色。 */
    public static final String ROLE_USER = "user";

    /** 助手消息角色。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 分页大小硬上限，防止客户端一次拉取超大结果集。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatSessionMapper sessionMapper;

    private final HistoryMessageMapper messageMapper;

    private final HistoryProperties properties;

    public ChatHistoryService(ChatSessionMapper sessionMapper,
                              HistoryMessageMapper messageMapper,
                              HistoryProperties properties) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.properties = properties;
    }

    /**
     * 一轮开始的返回：会话与已落库的 PENDING 用户消息。
     */
    public record TurnStart(ChatSession session, HistoryMessage userMessage) {
    }

    /**
     * 领域分页结果，不向上层暴露 MyBatis-Plus 的 Page 类型。
     */
    public record PageResult<T>(List<T> records, long total, long page, long size) {
    }

    /**
     * 解析开发身份标识。项目无真实认证，该标识仅用于开发环境归属隔离，不是鉴权凭据。
     *
     * @param headerValue 请求头原始值，空白时取默认归属
     */
    public String resolveOwner(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return properties.getDefaultOwner();
        }
        String value = headerValue.trim();
        if (value.length() > 64 || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("开发身份标识非法，仅允许字母数字、下划线与连字符，长度不超过64");
        }
        return value;
    }

    /**
     * 一轮开始（短事务）：创建或打开会话，并写入 PENDING 用户消息。
     *
     * @param owner               开发归属
     * @param requestedSessionId  客户端指定的会话标识，空表示新建会话
     * @param scene               角色场景，普通链路为空串
     * @param templateId          角色链路绑定模板，普通链路为空串
     * @param templateVersion     绑定时刻模板版本
     * @param userMessage         用户输入正文
     */
    @Transactional
    public TurnStart startTurn(String owner, String requestedSessionId, String scene,
                               String templateId, int templateVersion, String userMessage) {
        boolean openingExisting = StringUtils.hasText(requestedSessionId);
        // 已有会话：父行锁必须是事务内第一条语句，先于任何一致性读。
        // REPEATABLE READ 下普通 SELECT 会在首次读取时建立一致性读视图，若先执行
        // requireOwnedSession 的普通查询，FOR UPDATE 阻塞结束后同事务的 MAX 仍用旧视图，
        // 读不到并发事务已提交的序号，会导致唯一约束重复键。
        if (openingExisting) {
            sessionMapper.lockSessionForUpdate(requestedSessionId);
        }
        ChatSession session = openOrCreateSession(owner, requestedSessionId,
                nullToEmpty(scene), nullToEmpty(templateId), templateVersion, userMessage);
        if (!openingExisting) {
            // 新会话：父行由本事务插入后再加锁；INSERT 不创建一致性读视图，不影响后续取号
            sessionMapper.lockSessionForUpdate(session.getSessionId());
        }
        HistoryMessage user = newMessage(session, nextSequence(session.getSessionId()),
                ROLE_USER, userMessage, MessageStatus.PENDING);
        HistoryMessagePO userPO = toMessagePO(user);
        messageMapper.insert(userPO);
        // 自增主键回写领域对象，保证后续 updateById 能命中本行
        user.setId(userPO.getId());
        touchSession(session);
        sessionMapper.updateById(toSessionPO(session));
        log.info("对话轮次开始，sessionId={}，owner={}，sequence={}",
                session.getSessionId(), owner, user.getSequenceNo());
        return new TurnStart(session, user);
    }

    /**
     * 正常完成一轮（短事务）：用户消息置为 COMPLETED，插入 COMPLETED 助手消息。
     *
     * @param turn             startTurn 返回的轮次上下文
     * @param assistantContent 助手完整回复
     * @param modelName        产出回复的模型名
     */
    @Transactional
    public HistoryMessage completeTurn(TurnStart turn, String assistantContent, String modelName) {
        ChatSession session = turn.session();
        HistoryMessage user = turn.userMessage();
        // 父行 X 锁：与其他写事务串行，保证取号看到的是已提交的最大序号
        sessionMapper.lockSessionForUpdate(session.getSessionId());
        user.setStatus(MessageStatus.COMPLETED);
        user.setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(toMessagePO(user));

        HistoryMessage assistant = newMessage(session, nextSequence(session.getSessionId()),
                ROLE_ASSISTANT, assistantContent, MessageStatus.COMPLETED);
        assistant.setModelName(nullToEmpty(modelName));
        messageMapper.insert(toMessagePO(assistant));

        touchSession(session);
        sessionMapper.updateById(toSessionPO(session));
        log.info("对话轮次完成，sessionId={}，用户序号={}，助手序号={}",
                session.getSessionId(), user.getSequenceNo(), assistant.getSequenceNo());
        return assistant;
    }

    /**
     * 模型调用失败（短事务）：用户消息置为 FAILED 并记录安全错误信息，不伪造助手成功消息。
     */
    @Transactional
    public void failTurn(TurnStart turn, String errorCode, String errorMessage) {
        ChatSession session = turn.session();
        HistoryMessage user = turn.userMessage();
        user.setStatus(MessageStatus.FAILED);
        user.setErrorCode(safeTruncate(errorCode, 32));
        user.setErrorMessage(safeTruncate(errorMessage, 500));
        user.setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(toMessagePO(user));

        touchSession(session);
        sessionMapper.updateById(toSessionPO(session));
        log.warn("对话轮次失败，sessionId={}，errorCode={}，用户序号={}",
                session.getSessionId(), user.getErrorCode(), user.getSequenceNo());
    }

    /**
     * 流式输出中断（短事务）：用户消息置为 INTERRUPTED；存在部分正文时保留一条
     * INTERRUPTED 助手消息，无任何正文时不插入助手消息。
     */
    @Transactional
    public HistoryMessage interruptTurn(TurnStart turn, String partialContent, String modelName,
                                        String errorCode, String errorMessage) {
        ChatSession session = turn.session();
        HistoryMessage user = turn.userMessage();
        // 父行 X 锁：串行化同会话写事务，保证部分助手消息序号正确
        sessionMapper.lockSessionForUpdate(session.getSessionId());
        user.setStatus(MessageStatus.INTERRUPTED);
        user.setErrorCode(safeTruncate(errorCode, 32));
        user.setErrorMessage(safeTruncate(errorMessage, 500));
        user.setUpdatedAt(LocalDateTime.now());
        messageMapper.updateById(toMessagePO(user));

        HistoryMessage assistant = null;
        if (StringUtils.hasText(partialContent)) {
            assistant = newMessage(session, nextSequence(session.getSessionId()),
                    ROLE_ASSISTANT, partialContent, MessageStatus.INTERRUPTED);
            assistant.setModelName(nullToEmpty(modelName));
            assistant.setErrorCode(safeTruncate(errorCode, 32));
            assistant.setErrorMessage(safeTruncate(errorMessage, 500));
            messageMapper.insert(toMessagePO(assistant));
        }

        touchSession(session);
        sessionMapper.updateById(toSessionPO(session));
        log.warn("对话轮次中断，sessionId={}，保留助手部分内容={}",
                session.getSessionId(), assistant != null);
        return assistant;
    }

    /**
     * 选取送入模型的上下文窗口：最近 N 条 COMPLETED 消息，按序号正序返回。
     *
     * <p>若窗口首条为孤立 assistant 消息，则向前补一条 user，保证问答轮次完整。
     * 非成功状态消息一律不进入窗口，避免失败/中断内容污染下一轮上下文。</p>
     */
    public List<HistoryMessage> getContextWindow(String sessionId) {
        int limit = Math.max(1, properties.getContextWindowMessages());
        List<HistoryMessagePO> rows = messageMapper.selectList(
                new LambdaQueryWrapper<HistoryMessagePO>()
                        .eq(HistoryMessagePO::getSessionId, sessionId)
                        .eq(HistoryMessagePO::getStatus, MessageStatus.COMPLETED.name())
                        .orderByDesc(HistoryMessagePO::getSequenceNo)
                        .last("LIMIT " + limit));
        List<HistoryMessage> window = new ArrayList<>(rows.stream().map(this::toMessage).toList());
        Collections.reverse(window);

        if (!window.isEmpty() && ROLE_ASSISTANT.equals(window.get(0).getRole())) {
            HistoryMessage precedingUser = findPrecedingUser(sessionId, window.get(0).getSequenceNo());
            if (precedingUser != null) {
                List<HistoryMessage> expanded = new ArrayList<>(window.size() + 1);
                expanded.add(precedingUser);
                expanded.addAll(window);
                window = expanded;
            }
        }
        return window;
    }

    /**
     * 会话分页查询：仅返回当前归属的会话，按最近消息时间倒序。
     */
    public PageResult<ChatSession> pageSessions(String owner, int page, int size) {
        Page<ChatSessionPO> query = new Page<>(normalizePage(page), normalizeSize(size));
        Page<ChatSessionPO> result = sessionMapper.selectPage(query,
                new LambdaQueryWrapper<ChatSessionPO>()
                        .eq(ChatSessionPO::getOwnerId, owner)
                        .orderByDesc(ChatSessionPO::getLastMessageAt));
        List<ChatSession> records = result.getRecords().stream().map(this::toSession).toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 消息分页查询：先校验会话归属，再按序号正序返回。
     */
    public PageResult<HistoryMessage> pageMessages(String owner, String sessionId, int page, int size) {
        requireOwnedSession(owner, sessionId);
        Page<HistoryMessagePO> query = new Page<>(normalizePage(page), normalizeSize(size));
        Page<HistoryMessagePO> result = messageMapper.selectPage(query,
                new LambdaQueryWrapper<HistoryMessagePO>()
                        .eq(HistoryMessagePO::getSessionId, sessionId)
                        .orderByAsc(HistoryMessagePO::getSequenceNo));
        List<HistoryMessage> records = result.getRecords().stream().map(this::toMessage).toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 校验会话存在且归属当前身份；不存在、无权访问统一抛同一异常，不泄露资源是否存在。
     */
    public ChatSession requireOwnedSession(String owner, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("会话标识不能为空");
        }
        ChatSessionPO po = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionPO>()
                .eq(ChatSessionPO::getSessionId, sessionId)
                .last("LIMIT 1"));
        if (po == null || !owner.equals(po.getOwnerId())) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        return toSession(po);
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private ChatSession openOrCreateSession(String owner, String requestedSessionId, String scene,
                                            String templateId, int templateVersion, String userMessage) {
        if (StringUtils.hasText(requestedSessionId)) {
            ChatSession existing = requireOwnedSession(owner, requestedSessionId);
            if (!scene.equals(existing.getScene())) {
                throw new IllegalArgumentException("会话场景与当前请求不匹配，不同角色请新建会话");
            }
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        ChatSession session = new ChatSession();
        session.setSessionId(java.util.UUID.randomUUID().toString().replace("-", ""));
        session.setOwnerId(owner);
        session.setScene(scene);
        session.setTitle(buildTitle(userMessage));
        session.setTemplateId(templateId);
        session.setTemplateVersion(templateVersion);
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setLastMessageAt(now);
        ChatSessionPO sessionPO = toSessionPO(session);
        sessionMapper.insert(sessionPO);
        // 自增主键回写领域对象，后续轮次的 updateById 才能命中本会话
        session.setId(sessionPO.getId());
        return session;
    }

    private HistoryMessage newMessage(ChatSession session, int sequenceNo, String role,
                                      String content, MessageStatus status) {
        LocalDateTime now = LocalDateTime.now();
        HistoryMessage message = new HistoryMessage();
        message.setSessionId(session.getSessionId());
        message.setOwnerId(session.getOwnerId());
        message.setSequenceNo(sequenceNo);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setStatus(status);
        message.setModelName("");
        message.setErrorCode("");
        message.setErrorMessage("");
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        return message;
    }

    /**
     * 取当前最大序号并加 1。调用方必须已持有会话父行 X 锁，
     * 因此本方法使用普通 SELECT，不再加 FOR UPDATE。
     */
    private int nextSequence(String sessionId) {
        Integer max = messageMapper.selectMaxSequence(sessionId);
        return (max == null ? 0 : max) + 1;
    }

    private HistoryMessage findPrecedingUser(String sessionId, int beforeSequence) {
        HistoryMessagePO po = messageMapper.selectOne(new LambdaQueryWrapper<HistoryMessagePO>()
                .eq(HistoryMessagePO::getSessionId, sessionId)
                .eq(HistoryMessagePO::getStatus, MessageStatus.COMPLETED.name())
                .eq(HistoryMessagePO::getRole, ROLE_USER)
                .lt(HistoryMessagePO::getSequenceNo, beforeSequence)
                .orderByDesc(HistoryMessagePO::getSequenceNo)
                .last("LIMIT 1"));
        return po == null ? null : toMessage(po);
    }

    private void touchSession(ChatSession session) {
        LocalDateTime now = LocalDateTime.now();
        session.setUpdatedAt(now);
        session.setLastMessageAt(now);
    }

    private String buildTitle(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.strip().replaceAll("\\s+", " ");
        if (normalized.length() <= properties.getTitleMaxLength()) {
            return normalized;
        }
        return normalized.substring(0, properties.getTitleMaxLength());
    }

    private long normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    private long normalizeSize(int size) {
        if (size <= 0) {
            return properties.getDefaultPageSize();
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String safeTruncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------
    // PO <-> 领域模型
    // ------------------------------------------------------------------

    private ChatSessionPO toSessionPO(ChatSession session) {
        ChatSessionPO po = new ChatSessionPO();
        po.setId(session.getId());
        po.setSessionId(session.getSessionId());
        po.setOwnerId(session.getOwnerId());
        po.setScene(session.getScene());
        po.setTitle(session.getTitle());
        po.setTemplateId(session.getTemplateId());
        po.setTemplateVersion(session.getTemplateVersion());
        po.setStatus(session.getStatus().name());
        po.setCreatedAt(session.getCreatedAt());
        po.setUpdatedAt(session.getUpdatedAt());
        po.setLastMessageAt(session.getLastMessageAt());
        return po;
    }

    private ChatSession toSession(ChatSessionPO po) {
        ChatSession session = new ChatSession();
        session.setId(po.getId());
        session.setSessionId(po.getSessionId());
        session.setOwnerId(po.getOwnerId());
        session.setScene(po.getScene());
        session.setTitle(po.getTitle());
        session.setTemplateId(po.getTemplateId());
        session.setTemplateVersion(po.getTemplateVersion() == null ? 0 : po.getTemplateVersion());
        session.setStatus(SessionStatus.valueOf(po.getStatus()));
        session.setCreatedAt(po.getCreatedAt());
        session.setUpdatedAt(po.getUpdatedAt());
        session.setLastMessageAt(po.getLastMessageAt());
        return session;
    }

    private HistoryMessagePO toMessagePO(HistoryMessage message) {
        HistoryMessagePO po = new HistoryMessagePO();
        po.setId(message.getId());
        po.setSessionId(message.getSessionId());
        po.setOwnerId(message.getOwnerId());
        po.setSequenceNo(message.getSequenceNo());
        po.setRole(message.getRole());
        po.setContent(message.getContent());
        po.setStatus(message.getStatus().name());
        po.setModelName(message.getModelName());
        po.setErrorCode(message.getErrorCode());
        po.setErrorMessage(message.getErrorMessage());
        po.setCreatedAt(message.getCreatedAt());
        po.setUpdatedAt(message.getUpdatedAt());
        return po;
    }

    private HistoryMessage toMessage(HistoryMessagePO po) {
        HistoryMessage message = new HistoryMessage();
        message.setId(po.getId());
        message.setSessionId(po.getSessionId());
        message.setOwnerId(po.getOwnerId());
        message.setSequenceNo(po.getSequenceNo());
        message.setRole(po.getRole());
        message.setContent(po.getContent());
        message.setStatus(MessageStatus.valueOf(po.getStatus()));
        message.setModelName(po.getModelName());
        message.setErrorCode(po.getErrorCode());
        message.setErrorMessage(po.getErrorMessage());
        message.setCreatedAt(po.getCreatedAt());
        message.setUpdatedAt(po.getUpdatedAt());
        return message;
    }
}
