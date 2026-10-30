/*
 * D14 SSE（Server-Sent Events，服务器发送事件）分帧解析器。
 *
 * 设计目标：
 * 1. 零 DOM 依赖、零第三方依赖，浏览器与 Node（单元测试）环境均可加载；
 * 2. 兼容 WHATWG HTML 标准允许的三种行结束符：LF（\n）、CR（\r）、CRLF（\r\n），
 *    事件块之间以“空行”分隔（即 \n\n、\r\r、\r\n\r\n 三种等价形式）；
 * 3. 支持网络字节块边界任意切分，包括换行符本身被切到两次 feed、以及 UTF-8 多字节中文跨块
 *    （UTF-8 解码由调用方使用 TextDecoder(stream:true) 完成，本解析器只处理解码后的字符串）。
 *
 * 关键点：不能在每次 feed 时立刻把末尾的 \r 归一化为 \n——该 \r 可能属于被切块切开的 CRLF，
 * 前半块的 \r 必须暂存，与下一块首字符拼回后再判定，否则单个 CRLF 会被误判成空行。
 */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    } else {
        root.SseParser = api;
    }
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    function normalizeLineEndings(text) {
        // 先处理 CRLF，再处理遗留的单独 CR，统一归一化为 LF
        return text.split('\r\n').join('\n').split('\r').join('\n');
    }

    function createSseParser(onEvent) {
        var buffer = '';

        function emitBlock(block) {
            var eventName = 'message';
            var dataLines = [];
            var lines = block.split('\n');

            for (var i = 0; i < lines.length; i++) {
                var line = lines[i];
                if (line.indexOf('event:') === 0) {
                    eventName = line.slice('event:'.length).trim();
                } else if (line.indexOf('data:') === 0) {
                    var part = line.slice('data:'.length);
                    // SSE 规范：data: 后若紧跟一个空格，移除且仅移除这一个前导空格
                    dataLines.push(part.charAt(0) === ' ' ? part.slice(1) : part);
                }
            }

            if (dataLines.length === 0) {
                return;
            }
            // 同一事件内多行 data 按规范以 LF 拼接
            onEvent({ name: eventName, data: dataLines.join('\n') });
        }

        function drain(normalized) {
            var separatorIndex;
            while ((separatorIndex = normalized.indexOf('\n\n')) !== -1) {
                var block = normalized.slice(0, separatorIndex);
                normalized = normalized.slice(separatorIndex + 2);
                if (block.trim() !== '') {
                    emitBlock(block);
                }
            }
            return normalized;
        }

        return {
            /**
             * 喂入一段已解码文本，内部自动识别并回调完整事件块。
             */
            feed: function (text) {
                if (!text) {
                    return;
                }
                buffer += text;

                // 末尾若是 \r，可能是 CRLF 的前半部分，先摘出暂存，等待下一块判定
                var heldCarriageReturn = '';
                if (buffer.charAt(buffer.length - 1) === '\r') {
                    heldCarriageReturn = '\r';
                    buffer = buffer.slice(0, -1);
                }

                var normalized = normalizeLineEndings(buffer);
                var remainder = drain(normalized);
                buffer = remainder + heldCarriageReturn;
            },

            /**
             * 连接结束时调用：刷新残余的最后一个事件块（服务端可能未以空行结尾）。
             */
            flush: function () {
                var normalized = normalizeLineEndings(buffer);
                buffer = '';
                if (normalized.trim() === '') {
                    return;
                }
                var blocks = normalized.split('\n\n');
                for (var i = 0; i < blocks.length; i++) {
                    if (blocks[i].trim() !== '') {
                        emitBlock(blocks[i]);
                    }
                }
            },

            reset: function () {
                buffer = '';
            }
        };
    }

    return { createSseParser: createSseParser };
});
