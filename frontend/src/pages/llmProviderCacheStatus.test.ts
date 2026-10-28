import assert from 'node:assert/strict';
import test from 'node:test';

import {
  formatGraceRemaining,
  getCacheStatusPresentation,
  getReloadNotice,
  shouldPollCacheStatus,
} from './llmProviderCacheStatus.ts';

test('四种缓存状态翻译成约定文案，并带上对应的语气', () => {
  assert.equal(getCacheStatusPresentation('READY').label, '最新');
  assert.equal(getCacheStatusPresentation('REFRESHING').label, '刷新中');
  assert.equal(getCacheStatusPresentation('STALE').label, '已过期（宽限中）');
  assert.equal(getCacheStatusPresentation('EXPIRED').label, '已停止服务');

  assert.equal(getCacheStatusPresentation('READY').tone, 'success');
  assert.equal(getCacheStatusPresentation('REFRESHING').tone, 'refreshing');
  assert.equal(getCacheStatusPresentation('STALE').tone, 'warning');
  assert.equal(getCacheStatusPresentation('EXPIRED').tone, 'error');
});

test('状态缺失或后端新增了未知状态时回退成中性提示，而不是空白', () => {
  for (const state of [null, undefined, '', 'SOMETHING_NEW']) {
    const presentation = getCacheStatusPresentation(state);
    assert.equal(presentation.label, '状态未知');
    assert.equal(presentation.tone, 'neutral');
    assert.ok(presentation.description.length > 0);
  }
});

test('宽限剩余秒数只在有效正数时给出文案', () => {
  assert.equal(formatGraceRemaining(60), '剩余 60 秒');
  assert.equal(formatGraceRemaining(1), '剩余 1 秒');
  assert.equal(formatGraceRemaining(0), null);
  assert.equal(formatGraceRemaining(-5), null);
  assert.equal(formatGraceRemaining(null), null);
  assert.equal(formatGraceRemaining(undefined), null);
  assert.equal(formatGraceRemaining(Number.NaN), null);
});

test('刷新成功给出成功提示', () => {
  assert.deepEqual(getReloadNotice({ success: true }), {
    tone: 'success',
    text: '缓存已刷新',
  });
});

test('刷新失败时原样展示后端原因，并区分是否会自动重试', () => {
  assert.deepEqual(
    getReloadNotice({ success: false, failureReason: '服务商 dashscope 密钥解密失败', retryable: false }),
    {
      tone: 'error',
      text: '刷新失败：服务商 dashscope 密钥解密失败（请检查配置后手动再试）',
    },
  );

  assert.deepEqual(
    getReloadNotice({ success: false, failureReason: '数据库连接超时', retryable: true }),
    {
      tone: 'error',
      text: '刷新失败：数据库连接超时（系统会自动重试）',
    },
  );
});

test('刷新失败但没有原因时不吞掉失败，给出兜底文案', () => {
  assert.deepEqual(getReloadNotice({ success: false }), {
    tone: 'error',
    text: '刷新失败：原因未知（请检查配置后手动再试）',
  });
  assert.deepEqual(getReloadNotice({ success: false, failureReason: '   ' }), {
    tone: 'error',
    text: '刷新失败：原因未知（请检查配置后手动再试）',
  });
});

test('只有刷新中或宽限中才需要轮询状态', () => {
  assert.equal(shouldPollCacheStatus('REFRESHING'), true);
  assert.equal(shouldPollCacheStatus('STALE'), true);
  assert.equal(shouldPollCacheStatus('READY'), false);
  assert.equal(shouldPollCacheStatus('EXPIRED'), false);
  assert.equal(shouldPollCacheStatus(null), false);
});
