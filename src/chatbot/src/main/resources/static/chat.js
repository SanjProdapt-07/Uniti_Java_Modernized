// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

// Chat UI behaviour of the original Gradio app:
//  - the text box submits on Enter; a blank message is ignored
//  - the conversation (role/content pairs) is sent to the agent with every message
//  - a sample question starts a brand new conversation with an empty history
//  - assistant replies are rendered as Markdown; errors arrive as "Error: ..." text
(function () {
  'use strict';

  var chatbot = document.getElementById('chatbot');
  var form = document.getElementById('chat-form');
  var textbox = document.getElementById('textbox');
  var history = [];
  var busy = false;

  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function inline(s) {
    s = escapeHtml(s);
    s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>');
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
                  '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    return s;
  }

  // Minimal, safe Markdown: fenced code, headings, lists, paragraphs, inline code/bold/italic/links.
  function markdown(src) {
    var lines = String(src).replace(/\r\n/g, '\n').split('\n');
    var out = [];
    var i = 0;
    while (i < lines.length) {
      var line = lines[i];
      if (/^```/.test(line)) {
        var code = [];
        i++;
        while (i < lines.length && !/^```/.test(lines[i])) { code.push(lines[i]); i++; }
        i++;
        out.push('<pre><code>' + escapeHtml(code.join('\n')) + '</code></pre>');
      } else if (/^\s*[-*+]\s+/.test(line)) {
        var ul = [];
        while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
          ul.push('<li>' + inline(lines[i].replace(/^\s*[-*+]\s+/, '')) + '</li>'); i++;
        }
        out.push('<ul>' + ul.join('') + '</ul>');
      } else if (/^\s*\d+\.\s+/.test(line)) {
        var ol = [];
        while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
          ol.push('<li>' + inline(lines[i].replace(/^\s*\d+\.\s+/, '')) + '</li>'); i++;
        }
        out.push('<ol>' + ol.join('') + '</ol>');
      } else if (/^#{1,6}\s+/.test(line)) {
        var level = line.match(/^#+/)[0].length;
        out.push('<h' + level + '>' + inline(line.replace(/^#{1,6}\s+/, '')) + '</h' + level + '>');
        i++;
      } else if (line.trim() === '') {
        i++;
      } else {
        var para = [];
        while (i < lines.length && lines[i].trim() !== '' && !/^```/.test(lines[i]) &&
               !/^\s*([-*+]|\d+\.)\s+/.test(lines[i]) && !/^#{1,6}\s+/.test(lines[i])) {
          para.push(inline(lines[i])); i++;
        }
        out.push('<p>' + para.join('<br>') + '</p>');
      }
    }
    return out.join('');
  }

  function addMessage(role, text, pending) {
    var el = document.createElement('div');
    el.className = 'msg ' + role + (pending ? ' pending' : '');
    if (role === 'assistant' && !pending) {
      el.innerHTML = markdown(text);
    } else {
      el.textContent = text;
    }
    chatbot.appendChild(el);
    chatbot.scrollTop = chatbot.scrollHeight;
    return el;
  }

  function render() {
    chatbot.textContent = '';
    history.forEach(function (m) { addMessage(m.role, m.content, false); });
  }

  async function ask(message, fresh) {
    message = (message || '').trim();
    if (!message || busy) { return; }
    busy = true;
    // sample questions start over with an empty history
    var past = fresh ? [] : history.slice();
    if (fresh) {
      history = [];
      render();
    }
    addMessage('user', message, false);
    var pending = addMessage('assistant', 'Thinking...', true);
    var reply;
    try {
      var res = await fetch('api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: message, history: past })
      });
      if (!res.ok) { throw new Error(res.status + ' ' + res.statusText); }
      var data = await res.json();
      reply = data.reply;
    } catch (e) {
      reply = 'Error: ' + e.message;
    }
    pending.remove();
    if (reply === null || reply === undefined) {
      busy = false;
      return;
    }
    history = past.concat([{ role: 'user', content: message }, { role: 'assistant', content: reply }]);
    render();
    busy = false;
    textbox.focus();
  }

  form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var message = textbox.value;
    textbox.value = '';
    ask(message, false);
  });

  Array.prototype.forEach.call(document.querySelectorAll('.sample'), function (button) {
    button.addEventListener('click', function () {
      textbox.value = '';
      ask(button.getAttribute('data-question'), true);
    });
  });
})();
