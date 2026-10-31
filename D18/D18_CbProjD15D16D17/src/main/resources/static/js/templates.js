/**
 * D15 Prompt 模板管理页脚本（原生 JS）。
 *
 * 安全与口径：
 * - 所有来自服务端的字段一律 createTextNode/textContent 渲染，不拼 HTML 字符串；
 * - 变量声明在前端用 JSON 文本框编辑，保存前做本地 JSON 校验，最终合法性仍由服务端判定；
 * - 逻辑删除、停用等破坏性操作必须二次确认；服务端返回的业务错误按统一 Result 结构展示。
 */
(function () {
    'use strict';

    var API_BASE = '/api/v1/ai';

    var tbody = document.getElementById('templateTbody');
    var filterScene = document.getElementById('filterScene');
    var filterStatus = document.getElementById('filterStatus');
    var filterKeyword = document.getElementById('filterKeyword');
    var queryBtn = document.getElementById('queryBtn');
    var createBtn = document.getElementById('createBtn');

    var editorPanel = document.getElementById('editorPanel');
    var editorTitle = document.getElementById('editorTitle');
    var editTemplateId = document.getElementById('editTemplateId');
    var editName = document.getElementById('editName');
    var editScene = document.getElementById('editScene');
    var editDescription = document.getElementById('editDescription');
    var editContent = document.getElementById('editContent');
    var editVariables = document.getElementById('editVariables');
    var editError = document.getElementById('editError');
    var saveBtn = document.getElementById('saveBtn');
    var cancelBtn = document.getElementById('cancelBtn');

    /** 非空表示当前处于编辑模式；为空表示新建模式。 */
    var editingId = null;

    queryBtn.addEventListener('click', loadList);
    createBtn.addEventListener('click', openCreateEditor);
    saveBtn.addEventListener('click', saveEditor);
    cancelBtn.addEventListener('click', closeEditor);
    filterKeyword.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            loadList();
        }
    });

    loadList();

    function api(path, options) {
        return fetch(API_BASE + path, options || {}).then(function (resp) {
            return resp.json().then(function (body) {
                if (body.code !== 200) {
                    throw new Error(body.msg || '请求失败，code=' + body.code);
                }
                return body.data;
            });
        });
    }

    function buildQuery() {
        var params = new URLSearchParams();
        if (filterScene.value) {
            params.set('scene', filterScene.value);
        }
        if (filterStatus.value) {
            params.set('status', filterStatus.value);
        }
        if (filterKeyword.value.trim()) {
            params.set('keyword', filterKeyword.value.trim());
        }
        var qs = params.toString();
        return '/prompts' + (qs ? '?' + qs : '');
    }

    function loadList() {
        api(buildQuery()).then(function (list) {
            tbody.textContent = '';
            if (!list.length) {
                var tr = document.createElement('tr');
                var td = document.createElement('td');
                td.colSpan = 8;
                td.textContent = '没有符合条件的模板';
                td.style.color = '#9aa5b1';
                tr.appendChild(td);
                tbody.appendChild(tr);
                return;
            }
            list.forEach(function (item) {
                tbody.appendChild(buildRow(item));
            });
        }).catch(function (err) {
            alert('列表加载失败：' + err.message);
        });
    }

    function buildRow(item) {
        var tr = document.createElement('tr');

        tr.appendChild(cell(item.templateId));
        tr.appendChild(cell(item.name));
        tr.appendChild(cell(item.scene));
        tr.appendChild(cell('v' + item.version));

        var statusTd = document.createElement('td');
        var tag = document.createElement('span');
        tag.className = 'status-tag ' + item.status;
        tag.textContent = item.status;
        statusTd.appendChild(tag);
        tr.appendChild(statusTd);

        tr.appendChild(cell(String(item.variableCount)));
        tr.appendChild(cell(item.updatedAt || ''));

        var actionTd = document.createElement('td');
        var actions = document.createElement('div');
        actions.className = 'row-actions';

        actions.appendChild(actionButton('详情/编辑', 'btn-ghost', function () {
            openEditEditor(item.templateId);
        }));
        actions.appendChild(actionButton(
            item.status === 'ENABLED' ? '停用' : '启用',
            'btn-ghost',
            function () {
                toggleStatus(item);
            }
        ));
        actions.appendChild(actionButton('删除', 'btn-danger', function () {
            deleteTemplate(item.templateId);
        }));

        actionTd.appendChild(actions);
        tr.appendChild(actionTd);
        return tr;
    }

    function cell(text) {
        var td = document.createElement('td');
        td.textContent = text;
        return td;
    }

    function actionButton(text, cls, onClick) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn ' + cls;
        btn.textContent = text;
        btn.addEventListener('click', onClick);
        return btn;
    }

    function openCreateEditor() {
        editingId = null;
        editorTitle.textContent = '新建模板';
        editTemplateId.disabled = false;
        clearEditor();
        editError.textContent = '';
        editorPanel.style.display = 'block';
        editTemplateId.focus();
    }

    function openEditEditor(templateId) {
        api('/prompts/' + encodeURIComponent(templateId)).then(function (detail) {
            editingId = templateId;
            editorTitle.textContent = '编辑模板：' + templateId + '（保存后版本自增）';
            editTemplateId.disabled = true;
            editTemplateId.value = detail.templateId;
            editName.value = detail.name;
            editScene.value = detail.scene;
            editDescription.value = detail.description || '';
            editContent.value = detail.content;
            editVariables.value = JSON.stringify(detail.variables || [], null, 2);
            editError.textContent = '';
            editorPanel.style.display = 'block';
            window.scrollTo({ top: editorPanel.offsetTop, behavior: 'smooth' });
        }).catch(function (err) {
            alert('详情加载失败：' + err.message);
        });
    }

    function closeEditor() {
        editorPanel.style.display = 'none';
        editingId = null;
        clearEditor();
    }

    function clearEditor() {
        editTemplateId.value = '';
        editName.value = '';
        editDescription.value = '';
        editContent.value = '';
        editVariables.value = '[]';
        editError.textContent = '';
    }

    function saveEditor() {
        var variables;
        try {
            variables = JSON.parse(editVariables.value || '[]');
            if (!Array.isArray(variables)) {
                throw new Error('变量声明必须是 JSON 数组');
            }
        } catch (e) {
            editError.textContent = '变量声明 JSON 不合法：' + e.message;
            return;
        }

        var body = {
            templateId: editTemplateId.value.trim(),
            name: editName.value.trim(),
            scene: editScene.value,
            description: editDescription.value.trim(),
            content: editContent.value,
            variables: variables
        };

        var url = editingId
            ? '/prompts/' + encodeURIComponent(editingId)
            : '/prompts';
        var method = editingId ? 'PUT' : 'POST';

        saveBtn.disabled = true;
        api(url, {
            method: method,
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify(body)
        }).then(function () {
            editError.textContent = '';
            closeEditor();
            loadList();
        }).catch(function (err) {
            editError.textContent = '保存失败（服务端校验未通过）：' + err.message;
        }).finally(function () {
            saveBtn.disabled = false;
        });
    }

    function toggleStatus(item) {
        var next = item.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
        api('/prompts/' + encodeURIComponent(item.templateId) + '/status', {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify({ status: next })
        }).then(function () {
            loadList();
        }).catch(function (err) {
            alert('状态切换失败：' + err.message);
        });
    }

    function deleteTemplate(templateId) {
        if (!window.confirm('确认逻辑删除模板「' + templateId + '」？删除后不再出现在列表且不能发起新对话。')) {
            return;
        }
        api('/prompts/' + encodeURIComponent(templateId), { method: 'DELETE' })
            .then(function () {
                if (editingId === templateId) {
                    closeEditor();
                }
                loadList();
            })
            .catch(function (err) {
                alert('删除失败：' + err.message);
            });
    }
})();
