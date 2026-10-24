// Isolated browser fixture: no real account, model calls or persistent application data.
/* eslint-disable no-unused-expressions */
async page => {
  const base='http://127.0.0.1:5173'
  const check=(ok,message)=>{if(!ok)throw Error(message)}
  const errors=[];page.on('pageerror',error=>errors.push(error.message))
  const markdown='## 西湖建议\n\n**步行**与休息。\n\n| 天数 | 景点 |\n| --- | --- |\n| 一 | 西湖 |\n\n```js\nconst days = 3;\n```'
  const long='长篇原文\n'+('沿湖慢行，适时休息。'.repeat(250))+'完整原文末尾'
  const history=[
    {id:1,role:'user',content:'我的原始问题\n第二行'},
    {id:2,role:'assistant',content:markdown},
    {id:3,role:'assistant',content:long},
    {id:4,role:'user',content:'',image:'https://fixture.invalid/pixel.png'},
    {id:5,role:'assistant',content:''},
    {id:6,role:'assistant',content:'失败时保存的正文',turnStatus:'FAILED'},
    {id:7,role:'assistant',content:'停止时保存的正文',turnStatus:'CANCELLED'},
  ]
  const turns=[],requests=[];let mode='success',rejectNext=false,holdNext=false,release
  const responseText='AI 正文逐字显示，操作按钮等待输出结束。'
  await page.context().grantPermissions(['clipboard-read','clipboard-write'],{origin:base})
  await page.context().route('https://fixture.invalid/**',route=>route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><path fill="#a2d2ff" d="M0 0h40v40H0z"/></svg>'}))
  await page.context().route('**/api/**',async route=>{
    const req=route.request(),url=new URL(req.url()),path=url.pathname
    if(!path.startsWith('/api/'))return route.continue()
    let body
    if(path==='/api/auth/me')body={userId:72,role:'user',nickname:'消息操作验收'}
    else if(path==='/api/chat/conversations')body=[{id:1,title:'消息操作',updatedAt:'2026-10-24'},{id:2,title:'另一个会话',updatedAt:'2026-10-24'}]
    else if(path.endsWith('/messages'))body=path.includes('/1/')?history:[]
    else if(path.endsWith('/turns')&&path.includes('/conversations/'))body=path.includes('/1/')?turns:[]
    else if(path==='/api/chat/turns'&&req.method()==='POST'){
      const input=req.postDataJSON();requests.push(input)
      if(rejectNext){rejectNext=false;return route.fulfill({status:400,json:{code:'INVALID_REQUEST',message:'模拟拒绝，草稿应保留'}})}
      if(holdNext){holdNext=false;await new Promise(resolve=>{release=resolve})}
      body={turnId:input.turnId,conversationId:input.conversationId??1,status:'RUNNING',inputText:input.message,snapshot:{content:''},snapshotSeq:0,lastSeq:0,userMessageId:100+requests.length*2,assistantMessageId:null,createdAt:new Date().toISOString(),outcome:null,mode}
      turns.push(body)
    }else if(path.endsWith('/events')){
      const turn=turns.find(turn=>turn.turnId===path.split('/').at(-2))
      const delta={turnId:turn.turnId,seq:1,type:'delta',payload:responseText}
      const events=[delta]
      if(turn.mode!=='live'){
        const terminal={status:turn.mode==='failed'?'FAILED':'SUCCEEDED',outcome:'REPLIED',content:responseText,assistantMessageId:turn.userMessageId+1,error:null}
        turn.status=terminal.status;turn.snapshot={content:responseText,terminal};turn.lastSeq=2;turn.snapshotSeq=2;turn.assistantMessageId=terminal.assistantMessageId
        events.push({turnId:turn.turnId,seq:2,type:'terminal',payload:terminal})
      }else{turn.snapshot={content:responseText};turn.lastSeq=1;turn.snapshotSeq=1}
      const after=Number(url.searchParams.get('afterSeq'))
      return route.fulfill({contentType:'text/event-stream',body:events.filter(e=>e.seq>after).map(e=>`id: ${e.seq}\nevent: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join('')||': heartbeat\n\n'})
    }else if(path.endsWith('/cancel')){
      const turn=turns.find(turn=>turn.turnId===path.split('/').at(-2));turn.status='CANCELLED';turn.snapshot={content:responseText,terminal:{status:'CANCELLED',content:responseText,outcome:'CANCELLED',assistantMessageId:null,error:null}};body=turn
    }else if(path.startsWith('/api/chat/turns/'))body=turns.find(turn=>turn.turnId===path.split('/').at(-1))
    else return route.fulfill({status:404,json:{message:'Unknown fixture API'}})
    return route.fulfill({status:200,json:body})
  })
  await page.goto(base+'/login')
  await page.evaluate(async()=>{localStorage.clear();await new Promise(resolve=>{const req=indexedDB.deleteDatabase('travelscope-reconnect-v1');req.onsuccess=req.onerror=req.onblocked=resolve});localStorage.setItem('travelscope_token','message-actions-mock');localStorage.setItem('travelscope_user_id','72');localStorage.setItem('travelscope_role','user')})
  await page.setViewportSize({width:1200,height:900});await page.goto(base+'/')
  const row=id=>page.locator(`.message-row[data-message-id="${id}"]`)
  const input=page.locator('.input-area textarea')
  const reply=id=>row(id).getByRole('button',{name:'回复',exact:true})
  const copy=id=>row(id).getByRole('button',{name:'复制',exact:true})
  const send=()=>page.getByRole('button',{name:/^(发送|Send)$/}).click()
  async function ready(id){await page.waitForFunction(id=>!document.querySelector(`.message-row[data-message-id="${id}"] .message-actions button`)?.disabled,id)}
  async function finished(){await page.getByRole('button',{name:/^(发送|Send)$/}).waitFor();const last=turns.at(-1);await ready(last.assistantMessageId)}
  await ready(2)
  for(const id of [4,5]){check(await copy(id).isDisabled(),'Empty/image-only copy enabled');check(await reply(id).isDisabled(),'Empty/image-only reply enabled')}
  for(const id of [6,7])check(await copy(id).isEnabled(),'Saved failed/stopped body cannot be copied')
  for(const id of [1,2]){
    const geometry=await row(id).evaluate(el=>{const bubble=el.querySelector('.bubble').getBoundingClientRect(),actions=el.querySelector('.message-actions').getBoundingClientRect();return {below:actions.top>=bubble.bottom,left:Math.abs(actions.left-bubble.left),right:Math.abs(actions.right-bubble.right)}})
    check(geometry.below&&(id===1?geometry.right:geometry.left)<2,'Toolbar is not aligned below its bubble')
  }
  // Native clipboard -> actual paste, retaining Markdown exactly.
  await copy(2).click();await row(2).getByRole('button',{name:'已复制',exact:true}).waitFor()
  check((await page.evaluate(()=>navigator.clipboard.readText())).replace(/\r\n/g,'\n')===markdown,'Native copy included UI or lost Markdown')
  await input.focus();await input.press('Control+V');check(await input.inputValue()===markdown,'Ctrl+V did not paste copied body')
  await input.fill('已有草稿');await reply(2).click()
  check(await input.inputValue()==='已有草稿','Reply overwrote existing draft')
  check(await input.evaluate(el=>el===document.activeElement),'Reply did not focus composer')
  check(await page.locator('.reply-quote-text').textContent()===markdown,'Quote altered Markdown source')
  check(await page.locator('.reply-quote img').count()===0,'Quote executed Markdown images')
  await reply(1).click();check(await page.locator('.reply-quote').innerText().then(text=>text.includes('引用我的消息')),'Reply did not replace previous quote')
  await page.getByRole('button',{name:'取消引用',exact:true}).click();check(await input.inputValue()==='已有草稿','Cancel quote cleared draft')
  // Compatibility copy and selection/focus restoration, then explicit failure.
  await page.evaluate(()=>Object.defineProperty(navigator,'clipboard',{value:undefined,configurable:true}))
  await copy(1).focus();await copy(1).press('Enter')
  await row(1).getByRole('button',{name:'已复制',exact:true}).waitFor()
  check(await row(1).getByRole('button',{name:'已复制',exact:true}).evaluate(el=>el===document.activeElement),'Fallback did not restore focus')
  const selection=await page.evaluate(async()=>{
    const input=document.querySelector('.input-area textarea');input.focus();input.setSelectionRange(1,3,'backward')
    const {copyMessage}=await import('/src/chat/messageActions.ts');await copyMessage('兼容复制正文')
    return {focused:document.activeElement===input,start:input.selectionStart,end:input.selectionEnd,direction:input.selectionDirection,temporary:document.querySelectorAll('textarea[aria-hidden=true]').length}
  })
  check(selection.focused&&selection.start===1&&selection.end===3&&selection.direction==='backward'&&selection.temporary===0,'Compatibility copy lost editor selection or leaked its temporary field')
  await page.evaluate(()=>{document.execCommand=()=>false})
  await copy(6).click();await row(6).getByRole('status').getByText('复制失败，请手动选择正文复制。',{exact:true}).waitFor()
  check(await copy(6).isEnabled(),'Failed copy cannot be retried')
  await page.evaluate(()=>{delete navigator.clipboard;delete document.execCommand})
  // Long quote: original preview is complete; only the excerpt goes into the request.
  await reply(3).click();await page.getByText('已截取，发送时仅包含前 2000 字符',{exact:true}).waitFor()
  check((await page.locator('.reply-quote-text').textContent()).length===2000,'Wrong excerpt limit')
  await page.getByRole('button',{name:'展开原文',exact:true}).click();check(await page.locator('.reply-quote-text').textContent()===long,'Expanded original was truncated')
  await page.getByRole('button',{name:'收起原文',exact:true}).click()
  await input.fill('字'.repeat(8000));const count=requests.length;await send();await page.locator('.composer-error').waitFor();check(requests.length===count,'Oversized prompt reached backend')
  await input.fill('接着安排');await send();await finished()
  const quoted=requests.at(-1).message
  check(quoted.startsWith('引用 AI 的消息：\n> '),'Quote header absent')
  check(quoted.includes('\n> 已截取，发送时仅包含前 2000 字符\n\n本次问题：\n接着安排'),'Quote excerpt marker/question missing')
  check(!quoted.includes('完整原文末尾')&&requests.at(-1).sourceTurnId===null,'Reply reused form source or sent hidden tail')
  check(await page.locator('.reply-quote').count()===0&&await input.inputValue()==='','Accepted quote/draft not cleared')
  // Rejection and retry must retain the original editor state, without double quoting.
  await reply(2).click();await input.fill('失败后重试');rejectNext=true;await send()
  await page.getByText('模拟拒绝，草稿应保留',{exact:true}).waitFor()
  check(await input.inputValue()==='失败后重试'&&await page.locator('.reply-quote').count()===1,'Rejected draft not retained')
  const rejected=requests.at(-1).message;await send();await finished();check(requests.at(-1).message===rejected,'Retry duplicated the quote')
  // Image + quote with no question also survives rejection.
  await page.locator('input[type=file]').setInputFiles({name:'pixel.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=','base64')})
  await page.locator('.image-preview').waitFor();await reply(2).click();rejectNext=true;await send();await page.getByText('模拟拒绝，草稿应保留',{exact:true}).waitFor()
  check(await input.inputValue()===''&&await page.locator('.reply-quote').count()===1&&await page.locator('.image-preview').count()===1,'Image quote restored a second serialized quote')
  const imageRequest=requests.at(-1);await send();await finished();check(requests.at(-1).message===imageRequest.message&&Boolean(requests.at(-1).imageBase64),'Image retry lost image or duplicated quote')
  // Successful terminal is still locked until the real display buffer drains.
  await input.fill('打字机按钮状态');await send();await page.getByRole('button',{name:'发送',exact:true}).waitFor()
  let last=turns.at(-1);const lastRow=page.locator(`.message-row.assistant[data-turn-id="${last.turnId}"]`)
  check(await lastRow.getByRole('button',{name:'复制',exact:true}).isDisabled(),'Terminal enabled toolbar before typing finished')
  await finished()
  // Live task disables reply everywhere while completed history remains copyable.
  mode='live';await input.fill('持续生成');await send();await page.getByRole('button',{name:'停止',exact:true}).waitFor()
  check(await copy(2).isEnabled()&&await reply(2).isDisabled(),'History action locking does not match composer locking')
  last=turns.at(-1);const liveRow=page.locator(`.message-row.assistant[data-turn-id="${last.turnId}"]`)
  check(await liveRow.getByRole('button',{name:'复制',exact:true}).isDisabled(),'Running reply can be copied')
  await page.getByRole('button',{name:'停止',exact:true}).click();await page.getByRole('button',{name:'发送',exact:true}).waitFor()
  await page.waitForFunction(id=>!document.querySelector(`.message-row.assistant[data-turn-id="${id}"] .message-actions button`)?.disabled,last.turnId)
  mode='success'
  // Even editing/cancelling the current quote while acceptance is pending protects the draft.
  await reply(2).click();await input.fill('等待时编辑的草稿');holdNext=true;await send();await page.getByRole('button',{name:'停止',exact:true}).waitFor()
  await page.getByRole('button',{name:'取消引用',exact:true}).click();release();await finished()
  check(await input.inputValue()==='等待时编辑的草稿'&&await page.locator('.reply-quote').count()===0,'Acceptance cleared a draft edited after submission')
  // Accepted request from an old conversation cannot clear a newer draft/quote.
  await reply(2).click();await input.fill('旧会话等待确认');holdNext=true;await send();await page.getByRole('button',{name:'停止',exact:true}).waitFor()
  await page.locator('.conversation-item').nth(1).click();check(await page.locator('.reply-quote').count()===0,'Conversation switch retained quote')
  await input.fill('另一个会话的新草稿');release();await page.waitForTimeout(300)
  check(await input.inputValue()==='另一个会话的新草稿','Old acceptance cleared a new draft')
  await page.locator('.conversation-item').first().click();await ready(2)
  // Four themes, keyboard reply, narrow viewport and English labels.
  await reply(2).focus();await reply(2).press('Enter')
  await page.locator('.message-list').evaluate(el=>{el.scrollTop=0;el.dispatchEvent(new Event('scroll'))})
  for(const [theme,name] of [['light','浅色'],['dark','深色'],['peach','水蜜桃'],['blue-bowl','蓝白碗']]){
    await page.getByRole('button',{name:/^主题：/}).click();await page.getByRole('menuitemradio',{name,exact:true}).click()
    check(await page.locator('html').getAttribute('data-theme')===theme,'Theme not applied')
    await page.screenshot({path:`output/message-actions/${theme}.png`})
  }
  await page.setViewportSize({width:430,height:900})
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'Narrow layout overflowed')
  check(await page.locator('.input-area textarea').isVisible(),'Narrow composer unavailable')
  await page.screenshot({path:'output/message-actions/narrow.png'})
  await page.setViewportSize({width:1200,height:900})
  await page.getByRole('button',{name:'切换到英文',exact:true}).click()
  await page.getByRole('button',{name:'Cancel quote',exact:true}).waitFor()
  check(await row(2).getByRole('button',{name:'Reply',exact:true}).isEnabled(),'English action label missing')
  await input.fill('English question');await send();await finished()
  check(requests.at(-1).message.startsWith('Quoted AI message:\n> ')&&requests.at(-1).message.endsWith('My question:\nEnglish question'),'Prompt labels did not use current language')
  await row(2).getByRole('button',{name:'Reply',exact:true}).click()
  check(await page.locator('.reply-quote').count()===1,'Unsent quote fixture missing before reload')
  await page.reload();await row(2).waitFor()
  check(await page.locator('.reply-quote').count()===0,'Unsent quote was persisted')
  check(await page.locator('.message-row.user .bubble-content').allTextContents().then(texts=>texts.includes(quoted)),'Serialized quoted history disappeared after reload')
  const draftChecks=await page.evaluate(async()=>{const {verifyDrafts}=await import('/tests/message-draft.tsx');return verifyDrafts()})
  check(draftChecks.passed,'Draft race regression failed')
  check(errors.length===0,'Page errors: '+errors.join('; '))
  return {passed:true,requests:requests.length,draftChecks:draftChecks.checks,checks:['native clipboard and Ctrl+V','compatibility copy/focus and failure feedback','toolbar alignment and disabled states','draft and image preservation','long quote excerpt/expand and 8000-character limit','rejection and retry without duplicate quote','typewriter and live task locking','conversation switch and stale acceptance','four themes, English and narrow layout','reload history'],screenshots:'output/message-actions'}
}
