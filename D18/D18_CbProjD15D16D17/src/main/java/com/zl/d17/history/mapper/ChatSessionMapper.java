package com.zl.d17.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d17.history.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会话表 Mapper。单表 CRUD 由 {@link BaseMapper} 提供，不额外编写 XML。
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionPO> {

    /**
     * 对会话父行加排他记录锁（X 锁），必须在事务内调用。
     *
     * <p>同一会话的所有写事务先在该父行上排队，序号分配因此被完全串行化；
     * 相比直接对消息表做 {@code SELECT MAX ... FOR UPDATE}（会产生间隙锁，
     * 并发插入时 InnoDB 报 1213 死锁），父行锁只锁已存在的唯一记录，不产生间隙锁。</p>
     */
    @Select("SELECT id FROM chat_session WHERE session_id = #{sessionId} FOR UPDATE")
    Long lockSessionForUpdate(@Param("sessionId") String sessionId);
}
