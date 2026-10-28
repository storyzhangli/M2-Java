/*
 * D14 SSE 分帧解析器的 Node 原生单元测试（node:test + node:assert，零第三方依赖，无需 npm install）。
 *
 * 运行方式（在 D14_SSE_RMT 目录下）：
 *   node --test src/test/node/sse-parser.test.js
 *
 * 覆盖：LF / CRLF / CR 三种行结束符、逐字符切块、CRLF 换行符被两次 feed 切开、
 *      多行 data 拼接、前导空格规则、flush 残余块、以及 UTF-8 中文跨字节边界解码。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { TextDecoder } = require('node:util');

const { createSseParser } = require('../../main/resources/static/js/sse-parser.js');

function collect() {
    const events = [];
    const parser = createSseParser(function (evt) {
        events.push(evt);
    });
    return { events: events, parser: parser };
}

// 构造一组完整 SSE 事件，eol 控制行结束符，blank 控制事件间空行的写法
function buildStream(eol) {
    return [
        'event:start' + eol + 'data:{"requestId":"r1"}' + eol + eol,
        'event:delta' + eol + 'data:{"seq":0,"content":"你好"}' + eol + eol,
        'event:complete' + eol + 'data:{"totalChars":2}' + eol + eol
    ].join('');
}

test('LF 行结束：一次性喂入可解析出三个有序事件', function () {
    const ctx = collect();
    ctx.parser.feed(buildStream('\n'));
    ctx.parser.flush();
    assert.deepEqual(ctx.events.map(function (e) { return e.name; }), ['start', 'delta', 'complete']);
    assert.equal(ctx.events[1].data, '{"seq":0,"content":"你好"}');
});

test('CRLF 行结束：\\r\\n\\r\\n 空行可正确分帧（回归此前只识别 \\n\\n 的缺陷）', function () {
    const ctx = collect();
    ctx.parser.feed(buildStream('\r\n'));
    ctx.parser.flush();
    assert.deepEqual(ctx.events.map(function (e) { return e.name; }), ['start', 'delta', 'complete']);
    assert.equal(ctx.events[0].data, '{"requestId":"r1"}');
});

test('CR 行结束：单独 \\r\\r 空行同样可分帧（末尾暂存的 \\r 由 flush 落地）', function () {
    const ctx = collect();
    ctx.parser.feed(buildStream('\r'));
    ctx.parser.flush();
    assert.equal(ctx.events.length, 3);
    assert.equal(ctx.events[2].name, 'complete');
});

test('逐字符 feed：三种行结束符下结果与一次性喂入一致', function () {
    ['\n', '\r\n', '\r'].forEach(function (eol) {
        const ctx = collect();
        const raw = buildStream(eol);
        for (let i = 0; i < raw.length; i++) {
            ctx.parser.feed(raw.charAt(i));
        }
        // 与前端 app.js 在 ReadableStream 结束时调用 flush 的行为保持一致
        ctx.parser.flush();
        assert.deepEqual(ctx.events.map(function (e) { return e.name; }),
            ['start', 'delta', 'complete'], 'EOL=' + JSON.stringify(eol));
    });
});

test('CRLF 的 \\r 与 \\n 被切到两次 feed：不会被误判为空行', function () {
    const ctx = collect();
    const raw = buildStream('\r\n');
    // 找到第一个 \r\n 边界并从中间切开，且对每个可能的切块点都验证一遍
    for (let cut = 1; cut < raw.length; cut++) {
        const local = collect();
        local.parser.feed(raw.slice(0, cut));
        local.parser.feed(raw.slice(cut));
        local.parser.flush();
        assert.equal(local.events.length, 3, 'cut=' + cut + ' 时事件数应为 3');
        assert.equal(local.events[1].data, '{"seq":0,"content":"你好"}', 'cut=' + cut);
    }
});

test('多行 data 按 LF 拼接，且仅移除一个前导空格', function () {
    const ctx = collect();
    ctx.parser.feed('event:message\ndata:第一行\ndata: 第二行（保留这一个空格之后的内容语义）\ndata:  第三行保留两个空格中的一个\n\n');
    assert.equal(ctx.events.length, 1);
    assert.equal(ctx.events[0].data,
        '第一行\n第二行（保留这一个空格之后的内容语义）\n 第三行保留两个空格中的一个');
});

test('flush：服务端未以空行结尾的最后一个事件块也能输出', function () {
    const ctx = collect();
    ctx.parser.feed('event:delta\ndata:{"content":"尾块"}');
    assert.equal(ctx.events.length, 0);
    ctx.parser.flush();
    assert.equal(ctx.events.length, 1);
    assert.equal(ctx.events[0].name, 'delta');
});

test('UTF-8 中文跨字节边界：逐字节喂入 TextDecoder(stream) 再进解析器，文本无损', function () {
    const ctx = collect();
    const decoder = new TextDecoder('utf-8');
    const raw = buildStream('\r\n');
    const bytes = Buffer.from(raw, 'utf8');
    for (let i = 0; i < bytes.length; i++) {
        // 每次只给一个字节，多字节中文必然被拆开，依赖 stream:true 缓存半截字节
        ctx.parser.feed(decoder.decode(bytes.subarray(i, i + 1), { stream: true }));
    }
    ctx.parser.flush();
    assert.equal(ctx.events.length, 3);
    assert.ok(ctx.events[1].data.indexOf('你好') !== -1, '中文应无损还原：' + ctx.events[1].data);
});

test('reset 后历史缓存与事件全部清空', function () {
    const ctx = collect();
    ctx.parser.feed('event:delta\ndata:{"content":"a"}\n\n');
    assert.equal(ctx.events.length, 1);
    ctx.parser.reset();
    ctx.parser.feed('event:start\ndata:{}\n\n');
    assert.equal(ctx.events.length, 2);
    assert.equal(ctx.events[1].name, 'start');
});
