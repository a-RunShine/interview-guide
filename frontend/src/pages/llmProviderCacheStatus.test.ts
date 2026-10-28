import assert from 'node:assert/strict';
import test from 'node:test';

import { toReloadResult } from '../api/llmProviderReload.ts';
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

test('手动刷新失败时仍能从 Result.error 的 data 里读到真实的 retryable', () => {
  const failed = toReloadResult({
    code: 11004,
    message: '服务商 dashscope 密钥解密失败',
    data: {
      success: false,
      builtAt: null,
      failureReason: '服务商 dashscope 密钥解密失败',
      retryable: true,
    },
  });

  assert.deepEqual(failed, {
    success: false,
    builtAt: null,
    failureReason: '服务商 dashscope 密钥解密失败',
    retryable: true,
  });
  // 真实的 retryable 能一路走到提示文案里，而不是被硬编码成 false
  assert.deepEqual(getReloadNotice(failed), {
    tone: 'error',
    text: '刷新失败：服务商 dashscope 密钥解密失败（系统会自动重试）',
  });
});

test('手动刷新成功时保留构建时间', () => {
  const succeeded = toReloadResult({
    code: 200,
    message: 'success',
    data: { success: true, builtAt: '2026-10-28T08:00:00Z', failureReason: null, retryable: false },
  });

  assert.equal(succeeded.success, true);
  assert.equal(succeeded.builtAt, '2026-10-28T08:00:00Z');
});

test('响应里完全没有 data 时才退回失败 + 不可重试的兜底值', () => {
  assert.deepEqual(toReloadResult({ code: 500, message: '网络异常', data: null }), {
    success: false,
    builtAt: null,
    failureReason: '网络异常',
    retryable: false,
  });
  assert.deepEqual(toReloadResult(null), {
    success: false,
    builtAt: null,
    failureReason: null,
    retryable: false,
  });
});
