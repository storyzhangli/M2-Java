package com.zl.d17.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d17.history.po.HistoryMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 消息表 Mapper。单表 CRUD 由 {@link BaseMapper} 提供，不额外编写 XML。
 */
@Mapper
public interface HistoryMessageMapper extends BaseMapper<HistoryMessagePO> {

    /**
     * 返回目标会话当前最大序号；新会话无消息时返回 0。
     *
     * <p>本查询不加 {@code FOR UPDATE}：同会话并发写事务已由
     * {@link ChatSessionMapper#lockSessionForUpdate(String)} 的父行 X 锁串行化，
     * 取号期间不会有其他事务并发插入，避免间隙锁导致的 InnoDB 1213 死锁。</p>
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM chat_message WHERE session_id = #{sessionId}")
    Integer selectMaxSequence(@Param("sessionId") String sessionId);
}
