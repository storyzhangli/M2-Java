/**
 * D17 对话历史与多轮上下文关联页面脚本（原生 JS，无第三方依赖）。
 *
 * 关键约束：
 * 1. 角色清单来自服务端 GET /roles，前端只展示，最终是否允许对话由服务端模板状态决定；
 * 2. 切换角色必然重置会话：服务端禁止同一 sessionId 跨角色，前端先清空本地上下文；
 * 3. 所有服务端文本都通过 textContent/createTextNode 渲染，不拼 innerHTML，避免脚本注入；
 * 4. SSE 分帧交给独立的 SseParser.createSseParser（回调式 API），兼容 LF/CR/CRLF 与跨块边界，
 *    ReadableStream 结束时必须调用 flush() 刷新残余事件块；
 * 5. 会话标识由 start 事件回传，后续轮次原样带回，服务端据此从 COMPLETED 历史选取上下文窗口；
 * 6. thinking 事件渲染到独立“思考过程”区域，绝不追加进答案正文；citations 事件只做结构化来源列表。
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
    var thinkingToggle = document.getElementById('thinkingToggle');
    var showReasoningToggle = document.getElementById('showReasoningToggle');
    var retrieveToggle = document.getElementById('retrieveToggle');

    /** 服务端在首个 start 事件回传；为 null 表示尚未建立会话或使用无角色链路。 */
    var sessionId = null;
    var currentController = null;
    var sending = false;

    // ---- 初始化 ----
    loadRoles();
    loadHealth();

    // 思考开关关闭时，“展示推理过程”没有可展示对象，必须连带禁用并取消勾选
    thinkingToggle.addEventListener('change', function () {
        showReasoningToggle.disabled = !thinkingToggle.checked;
        if (!thinkingToggle.checked) {
            showReasoningToggle.checked = false;
        }
    });

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
                        + ' ｜ 上下文窗口：' + body.data.contextWindowMessages + ' 条';
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

    /**
     * 创建一条助手消息容器，内含三个互相独立的区域：
     * 思考过程区（默认隐藏）、最终答案气泡、引用来源区（默认隐藏）。
     */
    function createAssistantRegions() {
        if (emptyTip.parentNode === messageList) {
            messageList.removeChild(emptyTip);
        }
        var wrap = document.createElement('div');
        wrap.className = 'message assistant';

        var roleEl = document.createElement('div');
        roleEl.className = 'role';
        roleEl.textContent = 'AI 助手';

        var reasoningPanel = document.createElement('div');
        reasoningPanel.className = 'reasoning-panel';
        reasoningPanel.hidden = true;
        var reasoningTitle = document.createElement('div');
        reasoningTitle.className = 'reasoning-title';
        reasoningTitle.textContent = '思考过程（reasoning_content，默认不展示、不入会话记忆）';
        var reasoningBody = document.createElement('div');
        reasoningBody.className = 'reasoning-body';
        reasoningPanel.appendChild(reasoningTitle);
        reasoningPanel.appendChild(reasoningBody);

        var bubble = document.createElement('div');
        bubble.className = 'bubble';
        var cursor = document.createElement('span');
        cursor.className = 'cursor';
        bubble.appendChild(cursor);

        var citationPanel = document.createElement('div');
        citationPanel.className = 'citation-panel';
        citationPanel.hidden = true;
        var citationTitle = document.createElement('div');
        citationTitle.className = 'citation-title';
        citationTitle.textContent = '引用来源（仅来自本轮真实检索结果）';
        var citationList = document.createElement('ul');
        citationList.className = 'citation-list';
        citationPanel.appendChild(citationTitle);
        citationPanel.appendChild(citationList);

        wrap.appendChild(roleEl);
        wrap.appendChild(reasoningPanel);
        wrap.appendChild(bubble);
        wrap.appendChild(citationPanel);
        messageList.appendChild(wrap);
        messageList.scrollTop = messageList.scrollHeight;

        return {
            wrap: wrap,
            bubble: bubble,
            cursor: cursor,
            reasoningPanel: reasoningPanel,
            reasoningBody: reasoningBody,
            citationPanel: citationPanel,
            citationList: citationList
        };
    }

    function appendUserMessage(text) {
        if (emptyTip.parentNode === messageList) {
            messageList.removeChild(emptyTip);
        }
        var wrap = document.createElement('div');
        wrap.className = 'message user';
        var roleEl = document.createElement('div');
        roleEl.className = 'role';
        roleEl.textContent = '我';
        var bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.appendChild(document.createTextNode(text));
        wrap.appendChild(roleEl);
        wrap.appendChild(bubble);
        messageList.appendChild(wrap);
        messageList.scrollTop = messageList.scrollHeight;
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

        appendUserMessage(message);
        messageInput.value = '';
        setSendingState(true);
        setStatus('请求处理中，等待流式响应…', '');

        var regions = createAssistantRegions();
        var receivedText = '';
        var controller = new AbortController();
        currentController = controller;

        // D16 新增开关按请求传递；未勾选字段不传，服务端按旧行为处理
        var requestBody = {
            message: message,
            thinkingEnabled: thinkingToggle.checked,
            showReasoning: showReasoningToggle.checked,
            retrieveEnabled: retrieveToggle.checked
        };
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
                // 流开始前的参数错误（如非法 reasoningEffort）会返回普通 JSON，尽量读出安全错误文案
                return response.text().then(function (raw) {
                    var msg = 'HTTP 状态异常: ' + response.status;
                    try {
                        var body = JSON.parse(raw);
                        if (body && body.msg) {
                            msg = '[' + body.code + '] ' + body.msg;
                        }
                    } catch (ignore) {
                        // 非 JSON 响应保留原始状态文案
                    }
                    throw new Error(msg);
                });
            }
            var reader = response.body.getReader();
            var decoder = new TextDecoder('utf-8');
            var streamBroken = false;

            // 回调式解析器：完整事件在 onEvent 中逐个到达，feed 无返回值
            var parser = SseParser.createSseParser(function (evt) {
                var terminated = handleEvent(evt, regions, function (text) {
                    receivedText = text;
                }, receivedText);
                if (terminated) {
                    streamBroken = true;
                }
            });

            function pump() {
                return reader.read().then(function (result) {
                    if (result.done) {
                        parser.flush();
                        if (!streamBroken) {
                            finishAnswer(regions, receivedText, false);
                        }
                        return;
                    }
                    parser.feed(decoder.decode(result.value, { stream: true }));
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
                finishAnswer(regions, receivedText || '（已手动停止）', false);
                setStatus('已手动停止接收，当前会话仍保留在服务端', '');
                return;
            }
            finishAnswer(regions, (receivedText || '') + '\n[连接失败] ' + err.message, true);
            setStatus('流式连接失败：' + err.message, 'error');
        }).finally(function () {
            setSendingState(false);
            currentController = null;
        });
    }

    /**
     * 处理单个 SSE 事件。返回 true 表示流已因 complete/error 终止。
     */
    function handleEvent(evt, regions, updateText, currentText) {
        var payload = {};
        if (evt.data) {
            try {
                payload = JSON.parse(evt.data);
            } catch (e) {
                payload = { code: 5000, msg: '响应数据解析失败' };
            }
        }

        if (evt.name === 'start') {
            if (payload.sessionId) {
                sessionId = payload.sessionId;
                sessionTip.textContent = '当前会话：' + payload.sessionId
                    + (payload.templateId ? ' ｜ 模板：' + payload.templateId + ' v' + payload.templateVersion : '');
            }
            setStatus('连接已建立，思考模式=' + (payload.thinkingEnabled === true) + '，开始接收…', '');
            return false;
        }

        if (evt.name === 'thinking') {
            // 推理增量只进独立面板，与答案气泡物理隔离
            regions.reasoningPanel.hidden = false;
            regions.reasoningBody.appendChild(document.createTextNode(payload.content || ''));
            messageList.scrollTop = messageList.scrollHeight;
            return false;
        }

        if (evt.name === 'delta') {
            var nextText = currentText + (payload.content || '');
            regions.bubble.textContent = nextText;
            regions.bubble.appendChild(regions.cursor);
            updateText(nextText);
            return false;
        }

        if (evt.name === 'citations') {
            renderCitations(regions, Array.isArray(payload.citations) ? payload.citations : []);
            return false;
        }

        if (evt.name === 'complete') {
            finishAnswer(regions, currentText, false);
            setStatus('接收完成，共 ' + payload.totalChars + ' 字符，模式 ' + payload.mode
                + '，思考模式=' + (payload.thinkingEnabled === true), 'success');
            return true;
        }

        if (evt.name === 'error') {
            finishAnswer(regions,
                (currentText ? currentText + '\n' : '') + '[服务端错误 ' + payload.code + '] ' + payload.msg,
                true);
            setStatus('服务端返回错误：' + payload.code + ' ' + payload.msg, 'error');
            return true;
        }
        return false;
    }

    /**
     * 渲染结构化引用来源。全部使用 createElement/textContent，
     * 摘录、标题等服务端数据没有任何 HTML 执行面，也不展示本机路径与相似度分数。
     */
    function renderCitations(regions, citations) {
        if (citations.length === 0) {
            return;
        }
        regions.citationPanel.hidden = false;
        citations.forEach(function (item) {
            var li = document.createElement('li');
            li.className = 'citation-item';

            var head = document.createElement('div');
            head.className = 'citation-head';
            head.textContent = '[' + item.id + '] ' + (item.title || '未命名文档')
                + (item.section ? '｜' + item.section : '')
                + (typeof item.page === 'number' ? '｜第 ' + item.page + ' 页' : '');

            var excerpt = document.createElement('div');
            excerpt.className = 'citation-excerpt';
            excerpt.textContent = item.excerpt || '';

            var meta = document.createElement('div');
            meta.className = 'citation-meta';
            meta.textContent = 'documentId=' + item.documentId + '｜chunkId=' + item.chunkId
                + '｜sourceType=' + item.sourceType;

            li.appendChild(head);
            li.appendChild(excerpt);
            li.appendChild(meta);
            regions.citationList.appendChild(li);
        });
        messageList.scrollTop = messageList.scrollHeight;
    }

    function finishAnswer(regions, text, errorState) {
        regions.bubble.textContent = text || (errorState ? '（未收到有效响应）' : '（响应为空）');
        if (errorState) {
            regions.wrap.classList.add('error');
        }
        messageList.scrollTop = messageList.scrollHeight;
    }
})();
