/* oxlint-disable no-unused-expressions -- playwright-cli evaluates this function expression. */
async page => {
  const baseUrl = /^https?:/.test(page.url()) ? new URL(page.url()).origin : 'http://localhost:3000';
  const errors = [], unexpected = [], submissions = [];
  page.on('pageerror', error => errors.push(error.message));
  const conversation = { id: 991, title: '固定测试会话', updatedAt: '2026-10-19T00:00:00Z' };
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const fields = values => [
    ['destination', '目的地', 'text', '请输入目的地', '杭州'],
    ['days', '天数', 'number', '请输入天数', '3'],
    ['startDate', '出发日期', 'date', '', ''],
    ['fromCity', '出发城市', 'text', '请输入出发城市', ''],
  ].map(([name, label, type, placeholder, fallback]) => ({ name, label, type, placeholder,
    value: values[name] ?? fallback, filled: Boolean(values[name] ?? fallback), required: true, options: [] }));
  const event = (name, value) => 'event: ' + name + '\n' +
    String(typeof value === 'string' ? value : JSON.stringify(value)).split('\n').map(line => 'data: ' + line).join('\n') + '\n\n';
  const form = (values = {}, errors = []) => event('requirement_form', { fields: fields(values), hint: null, errors }) + event('done', '请确认行程需求');
  let rejected = false;
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (path === '/api/chat/conversations') return route.fulfill({ json: request.method() === 'POST' ? conversation : [conversation] });
    if (path === '/api/chat/conversations/991/messages') return route.fulfill({ json: [] });
    if (path === '/api/chat/stream') {
      const message = request.postDataJSON().message;
      let body;
      if (message.startsWith('{')) {
        const values = JSON.parse(message).__form__;
        submissions.push(values);
        if (values.__action__ === 'submit' && !rejected) {
          rejected = true;
          body = form(values, [{ field: 'destination', message: '测试地点校验失败，请修改目的地' }]);
        } else if (values.__action__ === 'cancel') body = event('done', '已取消规划。');
        else body = event('agent_status', { agent: 'planning-agent', action: '路线规划', state: 'SUCCESS' }) + event('done', '# 已生成行程\n第一天：湖畔散步\n\n第二天：室内博物馆');
      } else body = form();
      return route.fulfill({ contentType: 'text/event-stream; charset=utf-8', body });
    }
    unexpected.push(path);
    return route.abort();
  });
  await page.goto(baseUrl + '/login');
  await page.evaluate(() => {
    localStorage.setItem('travelscope_token', 'SYNTHETIC-UI-ONLY');
    localStorage.setItem('travelscope_role', 'user');
    localStorage.setItem('travelscope_nickname', '固定测试用户');
  });
  const openForm = async () => {
    await page.goto(baseUrl + '/');
    await page.getByRole('textbox').fill('请帮我规划杭州三日游');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await page.getByRole('button', { name: '开始规划', exact: true }).waitFor();
  };
  await openForm();
  assert(await page.getByRole('button', { name: '开始规划', exact: true }).isDisabled(), 'Missing required fields accepted');
  await page.locator('input[type=date]').fill('2026-10-24');
  await page.getByPlaceholder('请输入出发城市', { exact: true }).fill('上海');
  await page.getByRole('button', { name: '开始规划', exact: true }).click();
  await page.getByText('测试地点校验失败，请修改目的地', { exact: false }).waitFor();
  await page.getByPlaceholder('请输入目的地', { exact: true }).fill('苏州');
  await page.getByRole('button', { name: '开始规划', exact: true }).click();
  await page.getByRole('heading', { name: '已生成行程', exact: true }).waitFor();
  await page.getByText('第二天：室内博物馆', { exact: false }).waitFor();
  assert(submissions[1].destination === '苏州' && submissions[1].fromCity === '上海', 'Form edits were lost');
  await openForm();
  await page.setViewportSize({ width: 768, height: 1000 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Requirement form overflow');
  await page.getByRole('button', { name: '按推荐安排', exact: true }).click();
  await page.getByRole('heading', { name: '已生成行程', exact: true }).waitFor();
  assert(submissions.at(-1).__action__ === 'skip' && !submissions.at(-1).fromCity, 'Default request changed missing fields');
  await openForm();
  await page.getByRole('button', { name: '取消规划', exact: true }).click();
  await page.getByText('已取消规划。', { exact: true }).waitFor();
  assert(submissions.at(-1).__action__ === 'cancel', 'Cancel action lost');
  assert(!unexpected.length, 'Unexpected API requests: ' + unexpected.join(','));
  assert(!errors.length, errors.join('\n'));
  return { data: 'SYNTHETIC UI ONLY', flows: ['required-fields', 'edit-submit', 'place-validation-error', 'multiline-sse', 'defaults', 'cancel', 'responsive'], submissions: submissions.length, pageErrors: errors };
}
