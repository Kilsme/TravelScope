// CLI evaluates this function expression as its test entry point.
/* eslint-disable no-unused-expressions */
// Run from the repo root with playwright-cli run-code --filename=frontend/tests/pixel-ui.browser.js.
// All API requests are intercepted. No real account, database or model is used.
async (page) => {
  for (const other of page.context().pages()) if (other !== page) await other.close()
  const base = 'http://127.0.0.1:5173'
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const assert = (condition, message) => { if (!condition) throw Error(message) }
  const themeLabels = {light:'浅色',dark:'深色',peach:'水蜜桃','blue-bowl':'蓝白碗'}
  const stamp='2026-10-24T10:00:00'
  const fields = [{name:'destination',label:'目的地',labelKey:'requirements.destination.label',placeholder:'您想去哪个城市？',type:'text',value:'杭州',filled:true,required:true,options:[]},{name:'days',label:'天数',type:'number',value:'3',filled:true,required:true,options:[]}]
  const turnId='11111111-1111-4111-8111-111111111111'
  const prose='## 杭州三日游\n\n按自然风光与人文偏好安排，每天留出休息时间。\n\n| 天数 | 上午 | 下午 |\n| --- | --- | --- |\n| 第一天 | 西湖漫步 | 灵隐寺 |\n| 第二天 | 龙井茶村 | 九溪烟树 |\n| 第三天 | 浙江省博物馆 | 河坊街 |\n\n预算约 **2000 元**，具体以实际预订价格为准。\n\n用户原文与模型 emoji 保留：🌿 😊\n'
  const turn={turnId,conversationId:1,status:'SUCCEEDED',inputText:'帮我规划杭州三日游，喜欢自然和人文。',lastSeq:1,snapshotSeq:1,userMessageId:1,assistantMessageId:2,createdAt:stamp,outcome:'WAITING_INPUT',snapshot:{content:prose,requirementForm:{fields,hint:'请确认目的地与天数。',errors:[]},activities:[{agent:'travel-master',action:'需求已整理',state:'SUCCESS'}],reviewReport:JSON.stringify({passed:true,total:94,segments:[{segment:1,pass:true}],warnings:[]})}}
  let activeTurn=null, resolveStream, streamConnections=0
  const run={id:'run-1',source:'ONLINE',task_type:'PLANNING',status:'PLANNING_COMPLETED',started_at:stamp,ended_at:'2026-10-24T10:00:30',duration_ms:30000,tokens:4200,data_complete:true,usage_complete:true,artifact_available:false,span_count:3,failed_span_count:0}
  const spans=[{id:'span-1',kind:'AGENT',name:'travel-master',phase:'PLANNING',status:'SUCCEEDED',started_at:stamp,duration_ms:30000,aggregate_input_tokens:3000,aggregate_output_tokens:1200,aggregate_usage_complete:true,aggregate_cost:0.02,aggregate_cost_complete:true},{id:'span-2',parent_id:'span-1',kind:'MODEL',name:'model-main',model:'fixture-model',phase:'PLANNING',status:'SUCCEEDED',started_at:stamp,duration_ms:24000,input_tokens:3000,output_tokens:1200,usage_kind:'ACTUAL',cost:0.02,cost_complete:true},{id:'span-3',parent_id:'span-1',kind:'TOOL',name:'plan_route',phase:'PLANNING',status:'SUCCEEDED',started_at:stamp,duration_ms:1200}]
  await page.unroute('**/api/**')
  await page.context().unroute('**/api/**')
  await page.context().route('**/api/**', async route => {
    const req=route.request(), url=new URL(req.url()), path=url.pathname
    if (!path.startsWith('/api/')) { await route.continue();return }
    const identity=req.headers().authorization==='Bearer mock-admin'?{userId:99,role:'admin',nickname:'演示管理员'}:{userId:11,role:'user',nickname:'演示用户'}
    let body
    if(path==='/api/auth/me') body=identity
    else if(path==='/api/auth/login') { const admin=req.postDataJSON().username==='admin'; body={userId:admin?99:11,role:admin?'admin':'user',nickname:admin?'演示管理员':'演示用户',token:admin?'mock-admin':'mock-user'} }
    else if(path==='/api/auth/logout') body={}
    else if(path==='/api/chat/conversations') body=Array.from({length:30},(_,i)=>({id:i+1,title:i===0?'杭州三日游 · 自然与人文':`旅行问答 ${i+1}`,updatedAt:'2026-10-24 10:00'}))
    else if(path.endsWith('/messages')) body=[]
    else if(/\/conversations\/\d+\/turns$/.test(path)) body=[turn]
    else if(path==='/api/chat/turns' && req.method()==='POST') {
      const input=req.postDataJSON();activeTurn={...turn,turnId:input.turnId,status:'RUNNING',inputText:input.message,userMessageId:3,assistantMessageId:null,snapshot:{content:'正在生成测试回复'},lastSeq:0,snapshotSeq:0};body=activeTurn
    }
    else if(path.endsWith('/events')) {
      streamConnections++
      await new Promise(resolve=>{resolveStream=resolve})
      const terminal={status:'SUCCEEDED',outcome:'REPLIED',content:'测试回复完成',assistantMessageId:4,error:null}
      activeTurn={...activeTurn,status:'SUCCEEDED',lastSeq:1,snapshotSeq:1,snapshot:{content:terminal.content,terminal}}
      await route.fulfill({status:200,contentType:'text/event-stream',body:`id: 1\nevent: terminal\ndata: ${JSON.stringify({turnId:activeTurn.turnId,seq:1,type:'terminal',payload:terminal})}\n\n`});return
    }
    else if(path.startsWith('/api/chat/turns/')) body=activeTurn
    else if(path==='/api/admin/users') body=[{id:11,username:'traveler',nickname:'演示用户',role:'user',status:1,pointsBalance:200},{id:99,username:'admin',nickname:'演示管理员',role:'admin',status:1,pointsBalance:1000}]
    else if(path==='/api/admin/token-usage') body=[{userId:11,username:'traveler',date:'2026-10-24',modelName:'fixture-model',messageCount:8,totalTokens:4200}]
    else if(path==='/api/admin/documents') body=[{id:1,fileName:'杭州旅行指南.pdf',fileType:'pdf',fileSize:124000,status:'PARSED',createdAt:stamp}]
    else if(path==='/api/admin/invitation-codes') body=[{id:1,code:'DEMO1234',usedBy:null,usedAt:null,createdAt:stamp}]
    else if(path.endsWith('/overview')) body={sample_count:28,completed_plans:23,execution_success_rate:.96,p50_ms:30000,p95_ms:75000,mean_ms:42000,failure_rate:.04,timeout_rate:0,known_input_tokens:84000,known_output_tokens:28000,known_cost:.58,cost_complete:true,data_completeness:1,usage_completeness:1,tool_calls:36,ingestion:{enabled:true,degraded:false,queueDepth:0,droppedWrites:0,persistenceFailures:0,pendingIncompleteRuns:0}}
    else if(path.endsWith('/components')) body=[{kind:'AGENT',component_name:'travel-master',phase:'PLANNING',call_count:28,succeeded:27,failed:1,duration_samples:28,mean_ms:30000,p50_ms:28000,p95_ms:60000,input_tokens:84000,output_tokens:28000,known_cost:.58,cost_complete:true,usage_complete:true}]
    else if(path.endsWith('/runs')) body={items:[run],total:1,page:0,size:20}
    else if(path.endsWith('/runs/run-1')) body=run
    else if(path.endsWith('/spans')) body=spans
    else if(path.endsWith('/artifacts') || path.endsWith('/evaluations') || path.endsWith('/payload')) body=[]
    else if(path.endsWith('/batches')) body={items:[],total:0,page:0,size:20}
    else { await route.fulfill({status:404,json:{message:'Fixture: unavailable'}});return }
    await route.fulfill({status:200,json:body})
  })
  await page.goto(base+'/login')
  await page.evaluate(async()=>{localStorage.clear();await new Promise(resolve=>{const req=indexedDB.deleteDatabase('travelscope-reconnect-v1');req.onsuccess=req.onerror=req.onblocked=resolve})})
  async function login(username) {
    await page.goto(base+'/login')
    await page.getByRole('textbox').first().fill(username)
    await page.locator('input[type=password]').fill('fixture-only')
    await page.getByRole('button',{name:'登录',exact:true}).click()
    await page.waitForURL(username==='admin'?'**/admin/token-usage':base+'/')
  }
  async function selectTheme(theme) {
    await page.getByRole('button',{name:/^主题：/}).click()
    await page.getByRole('menuitemradio',{name:themeLabels[theme],exact:true}).click()
    assert(await page.locator('html').getAttribute('data-theme')===theme,'Theme selection failed: '+theme)
    assert(await page.getByRole('menu').count()===0,'Menu must close after choosing')
  }
  async function screenshot(name) {
    await page.evaluate(()=>document.fonts.ready)
    await page.mouse.move(1000,25)
    await page.screenshot({animations:'disabled',path:`output/pixel-ui/${name}.png`,fullPage:false})
  }
  await page.setViewportSize({width:1440,height:1000})
  await login('user')
  await page.locator('.req-form input').first().fill('苏州')
  await page.locator('textarea').fill('保留这段未发送的草稿')
  // Keyboard menu, radio selection and focus return.
  const trigger=page.getByRole('button',{name:/^主题：/})
  await trigger.focus();await page.keyboard.press('ArrowUp');await page.keyboard.press('End')
  assert(await page.getByRole('menuitemradio',{name:'蓝白碗',exact:true}).evaluate(el=>el===document.activeElement),'End must focus last theme')
  await page.keyboard.press('Escape')
  assert(await trigger.evaluate(el=>el===document.activeElement),'Escape must restore trigger focus')
  await trigger.click();await page.locator('.chat-header').click();assert(await page.getByRole('menu').count()===0,'Outside click must close menu')
  for(const theme of Object.keys(themeLabels)) {
    const node=await page.locator('textarea').elementHandle()
    await selectTheme(theme)
    assert(await page.locator('textarea').inputValue()==='保留这段未发送的草稿','Draft lost on theme switch')
    assert(await page.locator('.req-form input').first().inputValue()==='苏州','Form edit lost on theme switch')
    assert(await node.evaluate(el=>el.isConnected),'Chat input remounted on theme switch')
    await page.locator('.message-list').evaluate(el=>el.scrollTop=0)
    await screenshot(theme+'-chat')
  }
  const imageData=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;const ctx=canvas.getContext('2d');ctx.fillStyle='#ffcad4';ctx.fillRect(0,0,64,64);ctx.fillStyle='#9d8189';ctx.fillRect(16,16,32,32);return canvas.toDataURL().split(',')[1]})
  await page.locator('input[type=file]').setInputFiles({name:'pixel.png',mimeType:'image/png',buffer:Buffer.from(imageData,'base64')})
  await page.locator('.image-preview img').waitFor()
  const pendingImage=await page.locator('.image-preview img').getAttribute('src')
  await selectTheme('peach')
  assert(await page.locator('.image-preview img').getAttribute('src')===pendingImage,'Image preview lost on theme switch')
  await screenshot('peach-chat-image')
  await page.getByRole('button',{name:'移除图片',exact:true}).click()
  await page.getByRole('button',{name:'切换到英文',exact:true}).click()
  assert(await page.locator('textarea').inputValue()==='保留这段未发送的草稿','Language switch lost draft')
  assert(await page.locator('.req-form input').first().inputValue()==='苏州','Language switch lost form')
  await screenshot('peach-chat-english')
  await page.getByRole('button',{name:'Switch to Chinese',exact:true}).click()
  assert(await page.locator('.markdown-body').first().innerText().then(t=>t.includes('🌿 😊')),'Message emoji changed')
  assert(await page.locator('textarea').evaluate(el=>getComputedStyle(el).fontFamily.includes('Fusion Pixel')),'Pixel font not applied')
  assert(await page.evaluate(()=>document.fonts.check('16px "Fusion Pixel"')),'Pixel font failed to load')
  // A blocked mock SSE response remains attached while preferences change.
  await page.locator('textarea').fill('主题切换连接验收')
  await page.getByRole('button',{name:'发送',exact:true}).click()
  await page.waitForFunction(()=>document.querySelector('.stop-btn'))
  for(let i=0;i<30&&!resolveStream;i++) await new Promise(resolve=>setTimeout(resolve,50))
  assert(streamConnections===1,'Expected one active SSE subscription')
  for(const theme of ['dark','peach','blue-bowl','light']) await selectTheme(theme)
  assert(streamConnections===1,'Theme switch restarted SSE')
  resolveStream()
  await page.getByText('测试回复完成',{exact:true}).waitFor()
  // A second tab for the same account receives storage updates.
  const second=await page.context().newPage()
  await second.goto(base+'/login')
  await selectTheme('peach')
  await second.waitForFunction(()=>document.documentElement.dataset.theme==='peach')
  await second.close()
  await login('admin')
  assert(await page.locator('html').getAttribute('data-theme')==='light','Admin inherited user preference')
  await page.locator('input[type=date]').first().fill('2026-10-01')
  for(const theme of Object.keys(themeLabels)) {
    await selectTheme(theme)
    assert(await page.locator('input[type=date]').first().inputValue()==='2026-10-01','Admin filter lost')
    await screenshot(theme+'-admin')
  }
  for(const route of ['users','documents','invitation-codes','assessment']) {
    await page.goto(base+'/admin/'+route)
    await page.locator('.admin-page-head').waitFor()
    for (const theme of Object.keys(themeLabels)) { await selectTheme(theme);await screenshot(theme+'-admin-'+route) }
    assert(!/[🧪📊👥📄🎫]/u.test(await page.locator('.admin-nav').innerText()),'System emoji still visible')
  }
  await page.goto(base+'/admin/assessment?runId=run-1&detailTab=trace')
  await page.getByRole('dialog').waitFor()
  await page.locator('.as-node-details').waitFor()
  await screenshot('blue-bowl-trace')
  await page.setViewportSize({width:700,height:950})
  await screenshot('blue-bowl-trace-narrow')
  await page.keyboard.press('Escape')
  await page.goto(base+'/admin/users')
  await screenshot('blue-bowl-admin-narrow')
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Admin has horizontal page overflow')
  await page.setViewportSize({width:1440,height:1000})
  await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await page.waitForURL('**/login')
  assert(await page.locator('html').getAttribute('data-theme')==='light','Logout must reset anonymous preference')
  await screenshot('login')
  await page.goto(base+'/register');await screenshot('register')
  await login('user')
  assert(await page.locator('html').getAttribute('data-theme')==='peach','User preference not restored')
  await page.setViewportSize({width:390,height:844})
  await page.getByRole('button',{name:/^主题：/}).click()
  const menu=await page.getByRole('menu').boundingBox()
  assert(menu.x>=0&&menu.y>=0&&menu.x+menu.width<=390,'Narrow menu clipped')
  await screenshot('peach-chat-narrow-menu')
  await page.keyboard.press('Escape')
  await screenshot('peach-chat-narrow')
  assert(await page.locator('textarea').evaluate(el=>el.clientWidth)>150,'Narrow composer is unusably small')
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Chat has horizontal page overflow')
  assert(errors.length===0,'Page errors: '+errors.join('; '))
  return {passed:true,themes:4,screenshots:'output/pixel-ui',checks:['draft & form preservation','keyboard & outside close','unchanged SSE subscription','account isolation & logout','cross-tab sync','admin routes & filters','Trace drawer','font loading','narrow viewport']}
}
