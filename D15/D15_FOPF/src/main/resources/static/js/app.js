/**
 * D15 双角色流式对话页面脚本（原生 JS，无第三方依赖）。
 *
 * 关键约束：
 * 1. 角色清单来自服务端 GET /roles，前端只展示，最终是否允许对话由服务端模板状态决定；
 * 2. 切换角色必然重置会话：服务端禁止同一 sessionId 跨角色，前端先清空本地上下文；
 * 3. 所有服务端文本都通过 textContent/createTextNode 渲染，不拼 innerHTML，避免脚本注入；
 * 4. SSE 分帧交给独立的 createSseParser，兼容 LF/CR/CRLF 与跨块边界。
 */
(function () {
    'use strict';

    var API_BASE = '/api/v1/ai';
    var STREAM_PATH = API_BASE + '/chat/stream';

    var messageList = document.getElementById('messageList');
    var emptyTip = document.getElementById('emptyTip');
    var messageInput = document.getElementById('messageInput');
    var sendBtn = document.getElementById('sendBtn');
    var stopBtn = document.getElementById('stopBtn');
    var newChatBtn = document.getElementById('newChatBtn');
    var statusLine = document.getElementById('statusLine');
    var modeBadge = document.getElementById('modeBadge');
    var roleSelect = document.getElementById('roleSelect');
    var sessionTip = document.getElementById('sessionTip');

    /** 服务端在首个 start 事件回传；为 null 表示尚未建立会话或使用无角色链路。 */
    var sessionId = null;
    var currentController = null;
    var sending = false;

    // ---- 初始化 ----
    loadRoles();
    loadHealth();

    roleSelect.addEventListener('change', function () {
        // 角色切换 = 新会话，避免不同角色的历史互相污染
        resetConversation();
        setStatus('已切换为：' + (roleSelect.options[roleSelect.selectedIndex].textContent), '');
    });

    sendBtn.addEventListener('click', sendMessage);
    stopBtn.addEventListener('click', stopStreaming);
    newChatBtn.addEventListener('click', function () {
        resetConversation();
        setStatus('已开启新会话', 'success');
    });

    messageInput.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            sendMessage();
        }
    });

    /**
     * 拉取服务端启用中的角色清单填充下拉框。拉取失败不阻塞通用模式，只给出提示。
     */
    function loadRoles() {
        fetch(API_BASE + '/roles')
            .then(function (resp) { return resp.json(); })
            .then(function (body) {
                if (body.code !== 200 || !Array.isArray(body.data)) {
                    throw new Error(body.msg || '角色清单格式异常');
                }
                body.data.forEach(function (item) {
                    var option = document.createElement('option');
                    option.value = item.scene;
                    option.textContent = item.name + '（' + item.scene + '）';
                    roleSelect.appendChild(option);
                });
                // 默认选中第一个真实角色（索引 0 是“通用模式”）
                if (roleSelect.options.length > 1) {
                    roleSelect.selectedIndex = 1;
                }
            })
            .catch(function (err) {
                setStatus('角色清单加载失败，可使用通用模式：' + err.message, 'error');
            });
    }

    function loadHealth() {
        fetch(API_BASE + '/health')
            .then(function (resp) { return resp.json(); })
            .then(function (body) {
                if (body.code === 200 && body.data) {
                    modeBadge.textContent = '运行模式：' + body.data.mode
                        + ' ｜ 单条上限：' + body.data.maxMessageLength;
                } else {
                    modeBadge.textContent = '健康检查失败';
                }
            })
            .catch(function () {
                modeBadge.textContent = '健康检查不可用';
            });
    }

    function resetConversation() {
        if (sending && currentController) {
            currentController.abort();
        }
        sessionId = null;
        currentController = null;
        sending = false;
        messageList.innerHTML = '';
        messageList.appendChild(emptyTip);
        sessionTip.textContent = '尚未建立会话';
        sendBtn.disabled = false;
        stopBtn.disabled = true;
        setStatus('就绪', '');
    }

    function setStatus(text, level) {
        statusLine.textContent = text;
        statusLine.className = 'status-line' + (level ? ' ' + level : '');
    }

    function appendMessage(role, text, errorState) {
        if (emptyTip.parentNode === messageList) {
            messageList.removeChild(emptyTip);
        }
        var wrap = document.createElement('div');
        wrap.className = 'message ' + role + (errorState ? ' error' : '');

        var roleEl = document.createElement('div');
        roleEl.className = 'role';
        roleEl.textContent = role === 'user' ? '我' : 'AI 助手';

        var bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.appendChild(document.createTextNode(text));

        wrap.appendChild(roleEl);
        wrap.appendChild(bubble);
        messageList.appendChild(wrap);
        messageList.scrollTop = messageList.scrollHeight;
        return bubble;
    }

    function setSendingState(isSending) {
        sending = isSending;
        sendBtn.disabled = isSending;
        stopBtn.disabled = !isSending;
        messageInput.disabled = isSending;
    }

    function stopStreaming() {
        if (currentController) {
            currentController.abort();
        }
    }

    function sendMessage() {
        if (sending) {
            return;
        }
        var message = messageInput.value.trim();
        if (!message) {
            setStatus('消息不能为空或纯空白', 'error');
            messageInput.focus();
            return;
        }

        appendMessage('user', message);
        messageInput.value = '';
        setSendingState(true);
        setStatus('请求处理中，等待流式响应…', '');

        var assistantBubble = appendMessage('assistant', '');
        var cursor = document.createElement('span');
        cursor.className = 'cursor';
        assistantBubble.appendChild(cursor);

        var receivedText = '';
        var controller = new AbortController();
        currentController = controller;

        var requestBody = { message: message };
        var scene = roleSelect.value;
        if (scene) {
            requestBody.scene = scene;
        }
        // 首条消息不带 sessionId，由服务端生成并在 start 事件回传；后续轮次沿用同一标识
        if (sessionId) {
            requestBody.sessionId = sessionId;
        }

        fetch(STREAM_PATH, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify(requestBody),
            signal: controller.signal
        }).then(function (response) {
            if (!response.ok || !response.body) {
                throw new Error('HTTP 状态异常: ' + response.status);
            }
            var reader = response.body.getReader();
            var parser = createSseParser();
            var decoder = new TextDecoder('utf-8');
            var streamBroken = false;

            function pump() {
                return reader.read().then(function (result) {
                    if (result.done) {
                        if (!streamBroken) {
                            finishStream(assistantBubble, cursor, receivedText, false);
                        }
                        return;
                    }
                    var decoded = decoder.decode(result.value, { stream: true });
                    var events = parser.feed(decoded);
                    events.forEach(function (evt) {
                        streamBroken = handleEvent(evt, assistantBubble, cursor, function (text) {
                            receivedText = text;
                        }, receivedText);
                    });
                    if (streamBroken) {
                        reader.cancel().catch(function () {});
                        return;
                    }
                    return pump();
                });
            }

            return pump();
        }).catch(function (err) {
            if (err && err.name === 'AbortError') {
                finishStream(assistantBubble, cursor, receivedText || '（已手动停止）', false);
                setStatus('已手动停止接收，当前会话仍保留在服务端', '');
                return;
            }
            finishStream(assistantBubble, cursor,
                (receivedText || '') + '\n[连接失败] ' + err.message, true);
            setStatus('流式连接失败：' + err.message, 'error');
        }).finally(function () {
            setSendingState(false);
            currentController = null;
        });
    }

    /**
     * 处理单个 SSE 事件。返回 true 表示流已因 error 事件终止。
     */
    function handleEvent(evt, bubble, cursor, updateText, currentText) {
        var payload = {};
        if (evt.data) {
            try {
                payload = JSON.parse(evt.data);
            } catch (e) {
                payload = { code: 5000, msg: '响应数据解析失败' };
            }
        }

        if (evt.event === 'start') {
            if (payload.sessionId) {
                sessionId = payload.sessionId;
                sessionTip.textContent = '当前会话：' + sessionId
                    + (payload.templateId ? ' ｜ 模板：' + payload.templateId + ' v' + payload.templateVersion : '');
            }
            setStatus('连接已建立，开始接收增量内容…', '');
            return false;
        }

        if (evt.event === 'delta') {
            var nextText = currentText + (payload.content || '');
            bubble.textContent = nextText;
            bubble.appendChild(cursor);
            updateText(nextText);
            return false;
        }

        if (evt.event === 'complete') {
            finishStream(bubble, cursor, currentText, false);
            setStatus('接收完成，共 ' + payload.totalChars + ' 字符，模式 ' + payload.mode, 'success');
            return true;
        }

        if (evt.event === 'error') {
            finishStream(bubble, cursor,
                (currentText ? currentText + '\n' : '') + '[服务端错误 ' + payload.code + '] ' + payload.msg,
                true);
            setStatus('服务端返回错误：' + payload.code + ' ' + payload.msg, 'error');
            return true;
        }
        return false;
    }

    function finishStream(bubble, cursor, text, errorState) {
        bubble.textContent = text || (errorState ? '（未收到有效响应）' : '（响应为空）');
        if (cursor && cursor.parentNode === bubble) {
            bubble.removeChild(cursor);
        }
        if (errorState) {
            bubble.parentNode.classList.add('error');
        }
        messageList.scrollTop = messageList.scrollHeight;
    }
})();
