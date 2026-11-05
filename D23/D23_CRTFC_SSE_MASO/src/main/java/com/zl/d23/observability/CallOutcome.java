package com.zl.d23.observability;

/**
 * 一次逻辑模型调用的终态（Terminal Outcome）。
 *
 * <p>无论成功、异常还是客户端取消，每次调用都只允许落入其中一个终态，且整条调用链只写一条终态记录，
 * 避免成功后又补一条失败、或取消与完成重复计数。</p>
 */
public enum CallOutcome {

    /** 模型正常返回（同步拿到 ChatResponse，或流式 onComplete）。 */
    SUCCESS,

    /** 模型/网络/超时等异常终结（同步抛出，或流式 onError）。 */
    ERROR,

    /** 客户端在终态产生前取消订阅（流式 onCancel）。 */
    CANCELLED
}
