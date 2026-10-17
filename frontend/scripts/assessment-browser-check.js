/* oxlint-disable no-unused-expressions -- playwright-cli evaluates this function expression. */
async page => {
  const errors = [], calls = [], scopes = [];
  let created = false, status = 'RUNNING', batchReads = 0, createCalls = 0, scoreCalls = 0;
  let failOverview = false, failCases = false, slowOnline = false, edge = false, expired = false, expireOnScore = false, noCommon = false;
  const time = '2026-10-17T08:00:00Z';
  const score = { total: 86.7, threshold: 80, passed: true, score: { routeCoherence: 9, timeDensity: 8, preferenceMatch: 9, reason: '路线连贯，预算安排清晰。', evidence: ['北京与天津之间乘坐高铁'] } };
  const rules = [{ type: 'FIELD_COMPLETE', status: 'PASS', method: 'HEURISTIC', evidence: '已找到行程与预算信息' }, { type: 'BUDGET_SUM_AND_LIMIT', status: 'UNKNOWN', method: 'HARD_CONSTRAINT', evidence: '缺少独立费用项，无法判断预算合计' }];
  const run = (id = 'run-online-00', source = 'ONLINE') => ({ id, source, task_type: 'PLANNING', status: 'PLANNING_COMPLETED', data_complete: false, tokens: null, usage_complete: false, started_at: time, duration_ms: 2000, session_id: 'session-synthetic', artifact_available: true, planning_artifact_id: 'artifact-final', sample_id: source === 'ASSESSMENT' ? 'sample-multi' : null });
  const batch = (id, mode = 'FIXTURE', state = 'COMPLETED') => ({ id, tool_mode: mode, status: state, repetitions: 3, created_at: time, ended_at: state === 'COMPLETED' ? time : null });
  const batches = () => [...(created ? [batch('test-created', 'FIXTURE', status)] : []), batch('test-original'), batch('test-current'), batch('test-live', 'LIVE')];
  const overview = { sample_count: 128, completed_plans: 96, execution_success_rate: .94, p50_ms: 12000, p95_ms: 32000, failure_rate: .06, timeout_rate: .02, failed_mean_ms: 4000, data_completeness: .95, known_input_tokens: 28300, known_output_tokens: 12600, known_cost: .2431, cost_complete: false, usage_completeness: .8, tool_calls: 256, score_pass_rate: .8, rule_pass_rate: .7, overall_pass_rate: .65, hard_constraint_coverage: .6, mean_ms: 18000, stddev_ms: 5000, known_tool_cost: null, scoring_known_cost: .04, scoring_known_tokens: 2000, planning_known_cost: .2031, ingestion: { enabled: true, degraded: false, queueDepth: 0, droppedWrites: 0, persistenceFailures: 0, pendingIncompleteRuns: 0 } };
  const scenarios = [{ caseId: 'EC-001', category: 'MULTI_CITY', userMessages: ['北京和天津五日游，两人预算 8000 元。', '城市之间乘高铁。'], expectations: { rules: [{ desc: '行程覆盖两个城市' }] } }, { caseId: 'EC-018', category: 'TOOL_FAILURE', userMessages: ['天气查询失败时明确缺失证据。'], expectations: { rules: [] } }];
  page.on('pageerror', e => errors.push(String(e)));
  await page.route('**/api/admin/assessment/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname.replace('/api/admin/assessment', ''), method = route.request().method();
    calls.push({ path, method, query: url.search });
    let body;
    if (path === '/overview') {
      scopes.push([url.searchParams.get('source'), url.searchParams.get('toolMode')]);
      if (failOverview) return route.fulfill({ status: 503, json: {} });
      if (slowOnline && url.searchParams.get('source') === 'ONLINE') await new Promise(r => setTimeout(r, 500));
      body = edge ? { ...Object.fromEntries(Object.entries(overview).map(([key, value]) => [key, typeof value === 'number' ? null : value])), sample_count: 0, completed_plans: 0, tool_calls: 0, ingestion: { ...overview.ingestion, degraded: true } } : { ...overview, sample_count: url.searchParams.get('source') === 'ASSESSMENT' ? 24 : 128 };
    } else if (path === '/runs' && edge) body = [{ ...run(), status: 'FUTURE_STATUS', task_type: 'FUTURE_TASK', session_id: 'long-session-'.repeat(100) }];
    else if (path === '/runs') body = url.searchParams.get('page') === '1' ? [] : url.searchParams.get('sessionId') ? [run()] : Array.from({ length: 25 }, (_, i) => run('run-online-' + String(i).padStart(2, '0')));
    else if (/^\/runs\/[^/]+$/.test(path)) body = run(path.split('/')[2], path.includes('round') ? 'ASSESSMENT' : 'ONLINE');
    else if (path.endsWith('/spans')) body = [
      { id: 'span-parent', kind: 'AGENT', name: 'planning-agent', phase: 'PLANNING', status: 'SUCCEEDED', duration_ms: 2000, aggregate_input_tokens: 100, aggregate_output_tokens: 80, started_at: time, cost_complete: false },
      { id: 'span-model', parent_id: 'span-parent', kind: 'MODEL', name: 'synthetic-model', phase: 'PLANNING', status: 'FAILED', duration_ms: 1500, attempt_index: 1, usage_kind: 'UNKNOWN', input_tokens: null, output_tokens: null, cost_complete: false, started_at: time },
      { id: 'span-score', kind: 'MODEL', name: 'synthetic-judge', phase: 'SCORING', status: 'SUCCEEDED', duration_ms: 1000, started_at: '2026-10-17T08:00:10Z', cost: .01, cost_complete: true }
    ];
    else if (path.endsWith('/payload')) body = [{ id: 'payload-1', kind: 'INPUT', body: '[REDACTED] synthetic payload', created_at: time }];
    else if (path.endsWith('/artifacts') && expired) body = [];
    else if (path.endsWith('/artifacts')) body = [{ id: 'artifact-final', name: 'itinerary_draft.md', body: '# 最终行程\n北京与天津之间乘坐高铁', created_at: time }, { id: 'artifact-other', name: 'itinerary_draft.md', body: '# 不应选中的历史草稿', created_at: time }];
    else if (path.endsWith('/evaluations')) {
      if (method === 'POST' && expireOnScore) return route.fulfill({ status: 410, json: {} });
      if (method === 'POST') { scoreCalls++; body = { status: 'QUEUED' }; }
      else body = scoreCalls || path.includes('round') ? [{ id: 'score-new', status: 'SUCCEEDED', scorer_version: 'synthetic-v1', result_json: JSON.stringify(score), created_at: time }] : [{ id: 'score-old', status: 'ERROR', result_json: JSON.stringify({ errorType: 'SyntheticScorerError' }), created_at: time }];
    } else if (path === '/cases') {
      if (failCases) return route.fulfill({ status: 503, json: {} });
      body = scenarios;
    } else if (path === '/batches' && method === 'POST') {
      createCalls++; const input = route.request().postDataJSON();
      if (!input.requestKey || input.toolMode !== 'FIXTURE' || input.caseIds.length !== 2 || input.repetitions !== 3) throw new Error('Incorrect creation payload');
      created = true; body = { id: 'test-created' };
    } else if (path === '/batches') body = batches();
    else if (path.endsWith('/cancel')) { status = 'CANCELLED'; body = {}; }
    else if (path.endsWith('/retry-incomplete')) { status = 'RUNNING'; body = {}; }
    else if (path.includes('/batches/')) {
      batchReads++; body = { batch: batch('test-created', 'FIXTURE', status), samples: [
        { id: 'sample-multi', case_id: 'EC-001', repetition: 1, status: 'SUCCEEDED', rule_json: JSON.stringify(rules), score_status: 'SUCCEEDED', score_json: JSON.stringify(score) },
        { id: 'sample-failed', case_id: 'EC-018', repetition: 1, status: 'FAILED', rule_json: null, score_status: 'ERROR', score_json: null },
        { id: 'sample-pending', case_id: 'EC-001', repetition: 2, status: status === 'RUNNING' ? 'RUNNING' : status === 'CANCELLED' ? 'CANCELLED' : 'SUCCEEDED', rule_json: null, score_status: 'PENDING', score_json: null }
      ] };
    } else if (path.endsWith('/runs') && path.startsWith('/samples/')) body = [run('round-latest', 'ASSESSMENT'), run('round-previous', 'ASSESSMENT')];
    else if (path === '/comparisons' && noCommon) body = { baseline: batch('test-original'), candidate: batch('test-current'), configurationDiff: false, scenarios: [] };
    else if (path === '/comparisons') body = {
      baseline: { ...batch('test-original'), config_json: JSON.stringify({ models: { primary: 'model-a' }, rules: 'v1' }) },
      candidate: { ...batch('test-current'), config_json: JSON.stringify({ models: { primary: 'model-b' }, rules: 'v1' }) }, configurationDiff: true,
      scenarios: [{ batch_id: 'test-original', case_id: 'EC-001', samples: 3, completed_plans: 2, score_errors: 1, overall_pass_rate: .5, execution_success_rate: .8, p50_ms: 15000, known_cost: .2, cost_complete: false }, { batch_id: 'test-current', case_id: 'EC-001', samples: 3, completed_plans: 3, score_errors: 0, overall_pass_rate: .75, execution_success_rate: 1, p50_ms: 12000, known_cost: null, cost_complete: false }]
    }; else throw new Error('Unhandled assessment request: ' + method + ' ' + path);
    await route.fulfill({ json: body });
  });
  const assert = (ok, message) => { if (!ok) throw new Error(message); };
  const visible = text => page.getByText(text, { exact: true }).first().waitFor();
  const nav = name => page.getByRole('navigation', { name: '评估中心导航' }).getByRole('button', { name, exact: true }).click();
  await page.goto('http://localhost:3000/login');
  await page.evaluate(() => { localStorage.setItem('travelscope_token', 'SYNTHETIC-UI-TEST-NOT-A-CREDENTIAL'); localStorage.setItem('travelscope_role', 'admin'); localStorage.setItem('travelscope_nickname', '界面验证'); });
  await page.goto('http://localhost:3000/admin/assessment');
  await page.setViewportSize({ width: 1440, height: 1040 }); await visible('128');
  assert(!calls.some(x => ['/cases', '/batches', '/runs'].includes(x.path)), 'Overview fetched unrelated resources');
  await page.screenshot({ path: '../target/assessment-overview-1440.png', fullPage: true });
  for (const width of [1280, 768]) {
    await page.setViewportSize({ width, height: 1000 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Page overflow at ' + width);
    await page.screenshot({ path: '../target/assessment-overview-' + width + '.png', fullPage: true });
  }
  await page.setViewportSize({ width: 1440, height: 1040 });
  await page.getByRole('button', { name: '测试运行', exact: true }).click(); await visible('测试质量');
  assert(scopes.at(-1).join() === 'ASSESSMENT,FIXTURE', 'Incorrect default benchmark mode');
  await page.getByLabel('运行方式', { exact: true }).selectOption('LIVE'); await visible('当前保留记录的汇总 · 真实工具');
  await page.getByRole('button', { name: '日常使用', exact: true }).click(); await visible('128');
  assert(!await page.getByRole('heading', { name: '测试质量', exact: true }).count(), 'Online quality metrics appeared');
  failOverview = true; await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByRole('alert').waitFor();
  failOverview = false; await page.getByRole('button', { name: '重试', exact: true }).click(); await page.getByRole('alert').waitFor({ state: 'hidden' });
  slowOnline = true; await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByRole('button', { name: '测试运行', exact: true }).click();
  await visible('24'); await page.waitForTimeout(650); await visible('24'); slowOnline = false;
  await nav('运行记录'); await page.getByRole('button', { name: '查看运行 run-online-00', exact: true }).waitFor();
  await page.getByLabel('来源', { exact: true }).selectOption('ONLINE');
  await page.getByRole('button', { name: '下一页', exact: true }).click(); await visible('没有符合条件的运行记录');
  await page.getByRole('button', { name: '上一页', exact: true }).click(); await page.getByRole('button', { name: '查看运行 run-online-00', exact: true }).waitFor();
  const beforeTyping = calls.filter(x => x.path === '/runs').length;
  await page.getByLabel('会话编号', { exact: true }).fill('session-synthetic'); await page.waitForTimeout(100);
  assert(calls.filter(x => x.path === '/runs').length === beforeTyping, 'Session query fired before submit');
  await page.getByRole('button', { name: '查询', exact: true }).click(); await visible('本页 1 条记录');
  await page.getByRole('button', { name: '查看运行 run-online-00', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await dialog.getByRole('heading', { name: '最终行程', exact: true }).waitFor();
  assert(!await dialog.getByRole('heading', { name: '不应选中的历史草稿' }).count(), 'Wrong artifact selected');
  await dialog.getByText('评分失败', { exact: true }).waitFor();
  await dialog.getByRole('button', { name: '发起评分', exact: true }).click(); await dialog.getByText('评分达标', { exact: true }).waitFor();
  assert(scoreCalls === 1, 'Duplicate score request');
  await dialog.getByRole('button', { name: '调用过程', exact: true }).click(); await dialog.getByRole('heading', { name: '评分阶段', exact: true }).waitFor();
  await dialog.getByRole('button', { name: '查看调用内容（已脱敏）', exact: true }).first().click(); await dialog.getByText('[REDACTED] synthetic payload', { exact: true }).waitFor();
  await page.screenshot({ path: '../target/assessment-detail-1440.png', fullPage: true });
  await page.reload(); await page.getByRole('dialog').waitFor(); await page.keyboard.press('Escape'); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await visible('本页 1 条记录');
  await nav('测试任务'); await page.getByRole('button', { name: '新建测试', exact: true }).click(); dialog = page.getByRole('dialog');
  await dialog.getByRole('checkbox', { name: '全选场景', exact: true }).check();
  await dialog.getByRole('button', { name: '下一步 →', exact: true }).click();
  await dialog.getByLabel('每场景重复次数').fill('1.5'); assert(await dialog.getByRole('button', { name: '下一步 →', exact: true }).isDisabled(), 'Fractional repetitions accepted');
  await dialog.getByLabel('每场景重复次数').fill('3'); await dialog.getByRole('button', { name: '下一步 →', exact: true }).click();
  await dialog.getByText('6', { exact: true }).waitFor(); await dialog.getByRole('button', { name: '启动测试', exact: true }).click();
  await page.waitForURL('**batchId=test-created'); await visible('已处理 2 / 3'); assert(createCalls === 1, 'Duplicate creation');
  await page.reload(); await visible('已处理 2 / 3');
  await page.getByRole('button', { name: '查看场景 EC-001 第 1 次', exact: true }).click(); await page.waitForURL('**runId=round-latest');
  dialog = page.getByRole('dialog'); await dialog.getByText('缺少独立费用项，无法判断预算合计', { exact: true }).waitFor();
  await dialog.getByRole('button', { name: '第 1 轮', exact: false }).click(); await page.waitForURL('**runId=round-previous');
  await dialog.getByRole('button', { name: '第 1 轮', exact: false, pressed: true }).waitFor(); await dialog.getByRole('heading', { name: '最终行程', exact: true }).waitFor(); await dialog.getByText('评分达标', { exact: true }).waitFor(); await page.screenshot({ path: '../target/assessment-sample-1440.png', fullPage: true }); await page.keyboard.press('Escape');
  await page.getByRole('button', { name: '取消测试', exact: true }).click(); await visible('已处理 3 / 3');
  await page.getByRole('button', { name: '重跑未完成项', exact: true }).click(); await visible('已处理 2 / 3');
  status = 'COMPLETED'; await page.getByText('任务已结束，自动更新已停止', { exact: true }).waitFor({ timeout: 7000 });
  const stoppedAt = batchReads; await page.waitForTimeout(3400); assert(batchReads === stoppedAt, 'Terminal batch continued polling');
  await nav('结果对比'); await page.getByLabel('原测试', { exact: false }).selectOption('test-original');
  assert(!await page.getByLabel('新测试', { exact: false }).locator('option[value="test-live"]').count(), 'Cross-mode candidate allowed');
  await page.getByLabel('新测试', { exact: false }).selectOption('test-current'); await visible('+25.0 个百分点'); await visible('无法计算');
  await page.screenshot({ path: '../target/assessment-comparison-1440.png', fullPage: true });
  await page.goto('http://localhost:3000/admin/assessment?batchId=test-created'); await visible('任务已结束，自动更新已停止');
  assert(await page.getByRole('navigation', { name: '评估中心导航' }).getByRole('button', { name: '测试任务', exact: true }).getAttribute('aria-current') === 'page', 'Legacy link failed');
  failCases = true; await page.getByRole('button', { name: '新建测试', exact: true }).click(); await page.getByRole('dialog').getByRole('alert').waitFor();
  await page.keyboard.press('Escape'); assert(await page.getByRole('button', { name: '查看测试 test-created', exact: true }).count() === 1, 'Cases failure broke list');
  failCases = false;
  await page.goto('http://localhost:3000/admin/assessment?tab=runs&runId=run-online-00');
  dialog = page.getByRole('dialog');
  await dialog.getByRole('heading', { name: '最终行程', exact: true }).waitFor();
  await dialog.getByRole('button', { name: '关闭运行详情', exact: true }).focus();
  await page.keyboard.press('Shift+Tab');
  assert(await page.evaluate(() => Boolean(document.activeElement.closest('[role="dialog"]'))), 'Focus escaped dialog');
  await page.keyboard.press('Tab');
  assert(await page.evaluate(() => document.activeElement.getAttribute('aria-label') === '关闭运行详情'), 'Focus did not wrap to dialog start');
  expireOnScore = true;
  await dialog.getByRole('button', { name: '发起评分', exact: true }).click();
  await dialog.getByRole('alert').getByText('行程或需求正文未记录、已过保留期，暂时无法评分。', { exact: true }).waitFor();
  expired = true; await page.reload(); dialog = page.getByRole('dialog');
  await dialog.getByRole('heading', { name: '暂无可确认的最终行程', exact: true }).waitFor();
  assert(await dialog.getByRole('button', { name: '发起评分', exact: true }).isDisabled(), 'Expired artifact still eligible for scoring');
  await page.keyboard.press('Escape');
  noCommon = true;
  await page.goto('http://localhost:3000/admin/assessment?tab=compare&baselineId=test-original&candidateId=test-current');
  await visible('没有可以比较的共同场景');
  edge = true; await page.goto('http://localhost:3000/admin/assessment');
  await visible('此范围内暂无执行记录。可以切换统计来源，或新建测试。');
  await visible('暂无数据'); await visible('部分运行记录可能缺失，请结合记录完整情况判断结果。');
  await nav('运行记录'); await visible('未知状态');
  assert(!await page.getByText('FUTURE_STATUS', { exact: true }).count(), 'Unknown enum leaked into primary table');
  await page.getByRole('button', { name: '查看运行 run-online-00', exact: true }).click();
  await page.setViewportSize({ width: 768, height: 1000 });
  await page.getByRole('dialog').getByRole('button', { name: '技术信息', exact: true }).click();
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Detail caused page overflow');
  await page.keyboard.press('Escape');
  await page.setViewportSize({ width: 1440, height: 1040 });
  assert(!errors.length, errors.join('\n')); assert(scopes.every(([source, mode]) => source !== 'ASSESSMENT' || ['LIVE', 'FIXTURE'].includes(mode)), 'Mixed benchmark summary');
  return { data: 'SYNTHETIC UI ONLY — no live model calls', flows: ['scope', 'retry', 'request-race', 'pagination', 'session-submit', 'final-artifact', 'score', 'timeline', 'reload', 'wizard', 'legacy-link', 'multi-round', 'unknown-rule', 'cancel', 'rerun', 'poll-stop', 'comparison', 'resource-isolation', 'responsive', 'focus-trap', 'expired-score', 'empty-overview', 'unknown-status', 'no-common-scenarios'], createCalls, scoreCalls, pageErrors: errors, scopes };
}
