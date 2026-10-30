package com.zl.d17.history.model;

/**
 * 消息持久化状态（以字符串落库，避免枚举序号演进带来的兼容问题）。
 */
public enum MessageStatus {

    /** 已落库但模型调用尚未完成，用于失败/中断后的可追踪识别。 */
    PENDING,

    /** 完整成功的消息，可进入上下文窗口。 */
    COMPLETED,

    /** 模型调用失败，用户输入保留并记录错误码，不伪造成功回复。 */
    FAILED,

    /** 流式输出被超时/客户端断开中断，可能保留部分正文。 */
    INTERRUPTED
}
