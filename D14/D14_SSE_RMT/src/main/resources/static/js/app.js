/*
 * D14 SSE 流式对话前端（原生 JavaScript，无第三方依赖）。
 * 选型：接口为 POST JSON，浏览器原生 EventSource 仅支持 GET 且无法自由设置请求体，
 *      因此使用 fetch + ReadableStream 读取字节流，分帧交给 sse-parser.js（兼容 LF/CR/CRLF）。
 * 安全：模型输出与用户输入一律通过 textContent 渲染为纯文本，禁止 innerHTML 注入，避免 XSS。
 */
(function () {
    'use strict';

    var STREAM_URL = '/api/v1/ai/chat/stream';
    var HEALTH_URL = '/api/v1/ai/health';

    var messageList = document.getElementById('messageList');
    var emptyTip = document.getElementById('emptyTip');
    var messageInput = document.getElementById('messageInput');
    var sendBtn = document.getElementById('sendBtn');
    var stopBtn = document.getElementById('stopBtn');
    var newChatBtn = document.getElementById('newChatBtn');
    var statusLine = document.getElementById('statusLine');
    var modeBadge = document.getElementById('modeBadge');

    var abortController = null;
    var isStreaming = false;

    // 页面加载后探测当前后端模式（mock/deepseek），失败不阻塞对话功能
    fetch(HEALTH_URL)
        .then(function (resp) { return resp.json(); })
        .then(function (body) {
            if (body && body.data && body.data.mode) {
                modeBadge.textContent = '当前模式：' + body.data.mode;
            }
        })
        .catch(function () {
            modeBadge.textContent = '模式检测失败';
        });

    sendBtn.addEventListener('click', startStream);
    stopBtn.addEventListener('click', stopStream);
    newChatBtn.addEventListener('click', resetConversation);
    messageInput.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            startStream();
        }
    });

    function setStatus(text, level) {
        statusLine.textContent = text;
        statusLine.className = 'status-line' + (level ? ' ' + level : '');
    }

    function setStreamingState(streaming) {
        isStreaming = streaming;
        sendBtn.disabled = streaming;
        stopBtn.disabled = !streaming;
        messageInput.disabled = streaming;
    }

    function appendMessage(role) {
        if (emptyTip) {
            emptyTip.style.display = 'none';
        }
        var wrap = document.createElement('div');
        wrap.className = 'message ' + role;

        var roleLabel = document.createElement('div');
        roleLabel.className = 'role';
        roleLabel.textContent = role === 'user' ? '我' : 'AI 助手';

        var bubble = document.createElement('div');
        bubble.className = 'bubble';

        var cursor = null;
        if (role === 'assistant') {
            cursor = document.createElement('span');
            cursor.className = 'cursor';
            bubble.appendChild(cursor);
        }

        wrap.appendChild(roleLabel);
        wrap.appendChild(bubble);
        messageList.appendChild(wrap);
        scrollToBottom();
        return { wrap: wrap, bubble: bubble, cursor: cursor };
    }

    function scrollToBottom() {
        messageList.scrollTop = messageList.scrollHeight;
    }

    function removeCursor(ref) {
        if (ref && ref.cursor && ref.cursor.parentNode) {
            ref.cursor.parentNode.removeChild(ref.cursor);
        }
    }

    function resetConversation() {
        if (isStreaming && abortController) {
            abortController.abort();
        }
        messageList.querySelectorAll('.message').forEach(function (node) {
            node.parentNode.removeChild(node);
        });
        emptyTip.style.display = '';
        setStreamingState(false);
        abortController = null;
        setStatus('已开始新对话', 'success');
    }

    function stopStream() {
        if (abortController) {
            abortController.abort();
        }
        setStreamingState(false);
        setStatus('已手动中断请求', 'error');
    }

    function startStream() {
        var message = messageInput.value.trim();

        // 前端基础校验，最终以后端 @Valid 结果为准
        if (!message) {
            setStatus('请输入非空内容后再发送', 'error');
            messageInput.focus();
            return;
        }
        if (message.length > 2000) {
            setStatus('内容超过 2000 字符上限', 'error');
            return;
        }
        if (isStreaming) {
            return;
        }

        appendMessage('user').bubble.textContent = message;
        var assistantRef = appendMessage('assistant');

        setStreamingState(true);
        setStatus('正在建立 SSE 连接…');
        abortController = new AbortController();

        fetch(STREAM_URL, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify({ message: message }),
            signal: abortController.signal
        }).then(function (response) {
            var contentType = response.headers.get('Content-Type') || '';
            // 流未建立时后端可能返回统一 JSON 错误（如参数校验失败），而非 event-stream
            if (!response.ok || contentType.indexOf('text/event-stream') === -1) {
                return response.text().then(function (text) {
                    throw new Error(extractJsonMessage(text) || ('请求失败，HTTP ' + response.status));
                });
            }
            setStatus('连接已建立，等待模型增量…');
            return consumeStream(response.body, assistantRef);
        }).then(function (result) {
            removeCursor(assistantRef);
            setStreamingState(false);
            if (result === 'complete') {
                setStatus('本次回答已完成', 'success');
            } else if (result === 'error-event') {
                setStatus('服务端返回错误事件，本次回答未正常完成', 'error');
            } else {
                markError(assistantRef, '连接被中断，未收到完成事件');
                setStatus('连接异常中断', 'error');
            }
        }).catch(function (error) {
            removeCursor(assistantRef);
            setStreamingState(false);
            if (error.name === 'AbortError') {
                setStatus('请求已中断', 'error');
                return;
            }
            markError(assistantRef, error.message || '网络或服务异常');
            setStatus(error.message || '网络或服务异常', 'error');
        }).finally(function () {
            messageInput.value = '';
            scrollToBottom();
        });
    }

    /**
     * 读取 ReadableStream，交给独立的 SseParser 按 SSE 帧协议解析。
     * 解析器兼容 LF/CR/CRLF 三种行结束符与任意切块边界（见 sse-parser.js 及其 Node 测试）。
     * 返回 'complete' | 'error-event' | 'interrupted'。
     */
    function consumeStream(body, assistantRef) {
        var reader = body.getReader();
        var decoder = new TextDecoder('utf-8');
        var gotComplete = false;
        var gotError = false;

        // 分帧逻辑收敛到无 DOM 依赖的 SseParser，本处只负责 JSON 解析与页面分发
        var parser = SseParser.createSseParser(function (evt) {
            var payload;
            try {
                payload = JSON.parse(evt.data);
            } catch (e) {
                // 无法解析的事件直接忽略，不把原始字符串当作 HTML/文本注入
                return;
            }
            dispatchEvent(evt.name, payload, assistantRef,
                function () { gotError = true; },
                function () { gotComplete = true; });
        });

        function pump() {
            return reader.read().then(function (chunk) {
                if (chunk.done) {
                    parser.flush();
                    if (gotComplete) { return 'complete'; }
                    if (gotError) { return 'error-event'; }
                    return 'interrupted';
                }
                // stream:true 保证跨 chunk 的 UTF-8 多字节中文不会被截断成乱码
                parser.feed(decoder.decode(chunk.value, { stream: true }));
                scrollToBottom();
                return pump();
            });
        }

        return pump();
    }

    function dispatchEvent(eventName, payload, assistantRef, markErrorEvent, markComplete) {
        if (eventName === 'start') {
            setStatus('流式输出中… requestId=' + (payload.requestId || ''));
        } else if (eventName === 'delta') {
            if (typeof payload.content === 'string') {
                assistantRef.bubble.appendChild(document.createTextNode(payload.content));
            }
        } else if (eventName === 'complete') {
            markComplete();
            removeCursor(assistantRef);
        } else if (eventName === 'error') {
            markErrorEvent();
            removeCursor(assistantRef);
            markError(assistantRef, '[' + (payload.code || 'unknown') + '] ' + (payload.message || '服务端错误'));
        }
    }

    function markError(ref, message) {
        ref.wrap.classList.add('error');
        // 清空可能已追加的半截内容并以纯文本写入错误信息，保证失败不会被伪装成成功回答
        ref.bubble.textContent = '⚠ ' + message;
    }

    function extractJsonMessage(text) {
        try {
            var parsed = JSON.parse(text);
            return parsed.msg || (parsed.code ? '错误码 ' + parsed.code : null);
        } catch (e) {
            return null;
        }
    }
})();
