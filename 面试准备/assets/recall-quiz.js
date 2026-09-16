/* ============================================================
   recall-quiz.js — 提取练习（retrieval practice）自测组件
   用法：课程页底部引入
     <script src="../assets/recall-quiz.js" defer></script>

   作者只写声明式数据，组件负责渲染与交互：

   <div class="recall" data-lesson="0001">
     <div class="recall-q" data-q="问题文本" data-a="答案（可含简单 HTML）"></div>
     <div class="recall-q" data-q="..." data-a="..."></div>
   </div>

   设计意图：先强制自己回忆，再看答案，然后自评。
   「想一下再翻」本身就是记忆编码动作，比直接读答案有效得多。
   ============================================================ */

(function () {
  'use strict';

  var STORE_PREFIX = 'ig-recall:';
  var GRADES = [
    { key: 'hit',  label: '会了' },
    { key: 'mid',  label: '模糊' },
    { key: 'miss', label: '不会' }
  ];

  function loadState(id) {
    try {
      return JSON.parse(localStorage.getItem(STORE_PREFIX + id) || '{}');
    } catch (e) {
      return {};
    }
  }

  function saveState(id, state) {
    try {
      localStorage.setItem(STORE_PREFIX + id, JSON.stringify(state));
    } catch (e) {
      /* 隐私模式下 localStorage 可能不可用，静默降级为仅当次会话有效 */
    }
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function build(root) {
    var lessonId = root.getAttribute('data-lesson') || 'anon';
    var items = Array.prototype.slice.call(root.querySelectorAll('.recall-q'));
    if (!items.length) return;

    // 清空作者写的占位内容，保留一条标题
    var heading = root.querySelector('h3');
    root.textContent = '';

    var head = el('div', 'recall-head');
    head.appendChild(heading || el('h3', null, '主动回忆自测'));
    var score = el('span', 'recall-score', '尚未自评');
    head.appendChild(score);
    root.appendChild(head);
    root.appendChild(el('p', 'recall-hint',
      '先在心里（或纸上）把答案说出来，再点「看答案」。直接翻答案等于没练。'));

    var state = loadState(lessonId);
    var cards = [];

    items.forEach(function (src, i) {
      var q = src.getAttribute('data-q') || '';
      var a = src.getAttribute('data-a') || '';

      var item = el('div', 'recall-item');
      item.setAttribute('data-i', String(i));

      var qRow = el('div', 'recall-q');
      qRow.appendChild(el('span', 'idx', String(i + 1).padStart(2, '0')));
      qRow.appendChild(el('span', null, q));
      item.appendChild(qRow);

      var ans = el('div', 'recall-a');
      ans.innerHTML = a;
      item.appendChild(ans);

      var actions = el('div', 'recall-actions');
      var revealBtn = el('button', null, '看答案');
      revealBtn.type = 'button';
      actions.appendChild(revealBtn);

      GRADES.forEach(function (g) {
        var b = el('button', null, g.label);
        b.type = 'button';
        b.setAttribute('data-g', g.key);
        b.style.display = 'none';
        actions.appendChild(b);
      });

      item.appendChild(actions);
      root.appendChild(item);

      var card = { node: item, revealBtn: revealBtn, gradeBtns: GRADES.map(function (_, gi) {
        return actions.querySelectorAll('button[data-g]')[gi];
      }), grade: state[i] && state[i].grade };

      revealBtn.addEventListener('click', function () {
        item.classList.add('revealed');
        revealBtn.style.display = 'none';
        card.gradeBtns.forEach(function (b) { b.style.display = ''; });
        if (card.grade) applyGrade(card, card.grade);
      });

      card.gradeBtns.forEach(function (b) {
        b.addEventListener('click', function () {
          item.classList.add('revealed');
          revealBtn.style.display = 'none';
          card.gradeBtns.forEach(function (x) { x.style.display = ''; });
          var g = b.getAttribute('data-g');
          applyGrade(card, g);
          state[i] = { grade: g };
          saveState(lessonId, state);
          refreshScore();
        });
      });

      // 恢复上次状态
      if (card.grade) {
        item.classList.add('revealed');
        revealBtn.style.display = 'none';
        card.gradeBtns.forEach(function (b) { b.style.display = ''; });
        applyGrade(card, card.grade);
      }

      cards.push(card);
    });

    var foot = el('div', 'recall-foot');
    var tip = el('span', null, '「会了」＝能不看答案讲清楚；「模糊」＝想得起但要提示；「不会」＝说不出来。');
    foot.appendChild(tip);
    var reset = el('button', null, '清空自评');
    reset.type = 'button';
    reset.addEventListener('click', function () {
      saveState(lessonId, {});
      window.location.reload();
    });
    foot.appendChild(reset);
    root.appendChild(foot);

    function applyGrade(card, g) {
      card.node.classList.remove('grade-hit', 'grade-mid', 'grade-miss');
      card.node.classList.add('grade-' + g);
    }

    function refreshScore() {
      var counts = { hit: 0, mid: 0, miss: 0 };
      var rated = 0;
      cards.forEach(function (c) {
        if (c.grade && counts.hasOwnProperty(c.grade)) { counts[c.grade]++; rated++; }
      });
      if (!rated) { score.textContent = '尚未自评'; return; }
      score.textContent = '会了 ' + counts.hit + ' · 模糊 ' + counts.mid +
        ' · 不会 ' + counts.miss + '（共 ' + cards.length + ' 题）';
    }

    refreshScore();
  }

  function init() {
    Array.prototype.slice.call(document.querySelectorAll('.recall')).forEach(build);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
