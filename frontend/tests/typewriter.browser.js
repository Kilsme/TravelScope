// CLI entry point; APIs and image URLs are intercepted in this isolated browser session.
/* eslint-disable no-unused-expressions */
async page => {
  const base='http://127.0.0.1:5173'
  const errors=[];page.on('pageerror',error=>errors.push(error.message))
  const check=(ok,message)=>{if(!ok)throw Error(message)}
  const historyText='历史消息直接显示，不重新播放。\n\n'+Array.from({length:24},(_,i)=>`第 ${i+1} 条既有旅行建议。`).join('\n\n')
  let mode='live',active,releaseCancel
  const turns=[];const logs=new Map()
  const make=(id,content,status='SUCCEEDED')=>({turnId:id,conversationId:1,status,inputText:'演示提问',snapshot:{content},snapshotSeq:0,lastSeq:0,userMessageId:3,assistantMessageId:null,createdAt:new Date().toISOString(),outcome:null})
  function delta(text){const next={turnId:active.turnId,seq:++active.lastSeq,type:'delta',payload:text.slice(active.snapshot.content.length)};active.snapshot={content:text};active.snapshotSeq=active.lastSeq;logs.get(active.turnId).push(next)}
  function finish(status,content){const terminal={status,outcome:'REPLIED',content,assistantMessageId:4,error:null};active.status=status;active.snapshot={content,terminal};active.snapshotSeq=++active.lastSeq;logs.get(active.turnId).push({turnId:active.turnId,seq:active.lastSeq,type:'terminal',payload:terminal})}
  const initial='逐字输出示例：你好 👨‍👩‍👧‍👦 🇨🇳 e\u0301。'+('沿西湖步行，途中适当休息。'.repeat(3))
  await page.context().unroute('**/api/**')
  await page.context().route('https://fixture.invalid/**',route=>route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="240" height="80"><path fill="#d8e2dc" d="M0 0h240v80H0z"/><path fill="#9d8189" d="M20 20h200v40H20z"/></svg>'}))
  await page.context().route('**/api/**',async route=>{
    const req=route.request(),url=new URL(req.url()),path=url.pathname
    if(!path.startsWith('/api/'))return route.continue()
    let body
    if(path==='/api/auth/me')body={userId:71,role:'user',nickname:'打字机验收'}
    else if(path==='/api/chat/conversations')body=[{id:1,title:'打字机验收',updatedAt:'2026-10-24'},{id:2,title:'历史消息',updatedAt:'2026-10-23'}]
    else if(path.endsWith('/messages'))body=[{id:1,role:'assistant',content:historyText}]
    else if(path.endsWith('/conversations/1/turns'))body=turns
    else if(path.endsWith('/conversations/2/turns'))body=[]
    else if(path==='/api/chat/turns'&&req.method()==='POST'){
      const input=req.postDataJSON();active=make(input.turnId,'','RUNNING');active.inputText=input.message;turns.push(active);logs.set(active.turnId,[]);body=active
    }else if(path.endsWith('/cancel')){
      await new Promise(resolve=>{releaseCancel=resolve})
      finish('CANCELLED','取消后服务器确认的正文。');body=active
    }else if(path.endsWith('/events')){
      const id=path.split('/').at(-2),log=logs.get(id)
      if(log.length===0){delta(initial);if(mode==='success')finish('SUCCEEDED',initial+'最终结论。');if(mode==='rewrite')finish('SUCCEEDED','服务器改写后的权威终稿。');if(mode==='failed')finish('FAILED','生成失败时已确认的部分正文。')}
      const events=log.filter(event=>event.seq>Number(url.searchParams.get('afterSeq')))
      await route.fulfill({contentType:'text/event-stream',body:events.map(event=>`id: ${event.seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join('')||': heartbeat\n\n'});return
    }else if(path.startsWith('/api/chat/turns/'))body=turns.find(turn=>turn.turnId===path.split('/').at(-1))
    else return route.fulfill({status:404,json:{message:'Unknown mock API'}})
    await route.fulfill({status:200,json:body})
  })
  await page.goto(base+'/login')
  await page.evaluate(async()=>{localStorage.clear();await new Promise(resolve=>{const req=indexedDB.deleteDatabase('travelscope-reconnect-v1');req.onsuccess=req.onerror=req.onblocked=resolve});localStorage.setItem('travelscope_token','typewriter-mock');localStorage.setItem('travelscope_user_id','71');localStorage.setItem('travelscope_role','user')})
  await page.setViewportSize({width:1200,height:850})
  await page.goto(base+'/')
  await page.locator('.markdown-body').first().waitFor()
  check(await page.locator('.typewriter-message').first().getAttribute('data-typing')==='false','History replayed')
  async function send(nextMode){
    mode=nextMode
    await page.locator('textarea').fill(nextMode+' 测试')
    const accepted=page.waitForResponse(response=>response.url().endsWith('/api/chat/turns')&&response.request().method()==='POST')
    await page.getByRole('button',{name:'发送',exact:true}).click()
    const turn=await (await accepted).json()
    const node=page.locator(`.message-row.assistant[data-turn-id="${turn.turnId}"] .typewriter-message`)
    await node.waitFor()
    return node
  }
  async function settle(node){await node.locator('.markdown-body').waitFor();await page.waitForFunction(id=>document.querySelector(`.message-row.assistant[data-turn-id="${id}"] .typewriter-message`)?.dataset.typing==='false',active.turnId)}
  async function image(name){await page.screenshot({path:`output/typewriter/${name}.png`})}
  let node=await send('live')
  await page.waitForFunction(()=>document.querySelector('.message-row.assistant[data-turn-id] .typewriter-message')?.dataset.typing==='true')
  await node.locator('.markdown-body').waitFor()
  const first=await node.innerText()
  check(first.length>0&&first.length<initial.length,'Live source did not reveal progressively')
  async function checkCursor(node){
    const position=await node.locator('.typewriter-cursor').evaluate(cursor=>{
      const text=cursor.previousSibling
      if(text?.nodeType!==Node.TEXT_NODE||!text.textContent.length)return null
      const range=document.createRange();range.setStart(text,text.textContent.length-1);range.setEnd(text,text.textContent.length)
      const end=range.getBoundingClientRect(),caret=cursor.getBoundingClientRect()
      return {gap:caret.left-end.right,overlap:Math.min(caret.bottom,end.bottom)-Math.max(caret.top,end.top)}
    })
    check(position&&position.gap>=-1&&position.gap<=4&&position.overlap>0,'Cursor detached from the final character: '+JSON.stringify(position))
  }
  await checkCursor(node)
  const before=await node.locator('.markdown-body').textContent()
  await page.waitForTimeout(240)
  const after=await node.locator('.markdown-body').textContent()
  check(after.length-before.length<=4,'Prose accelerated instead of revealing individual characters')
  await image('during-output')
  // Preferences must not restart the component-local animation.
  await page.getByRole('button',{name:/^主题：/}).click();await page.getByRole('menuitemradio',{name:'水蜜桃',exact:true}).click()
  await settle(node)
  await checkCursor(node)
  // Scroll up, then append new source through reconnect/replay.
  await page.locator('.message-list').evaluate(el=>{el.scrollTop=0;el.dispatchEvent(new Event('scroll'))})
  const extended=initial+'\n\n滚动追加：用户正在阅读上文时，不抢走滚动位置。'
  delta(extended)
  await page.waitForFunction(()=>[...document.querySelectorAll('.message-row.assistant[data-turn-id]')].at(-1).innerText.includes('滚动追加'))
  check(await page.locator('.message-list').evaluate(el=>el.scrollTop)<2,'Incoming text forced the reader to the bottom')
  await settle(node)
  await page.locator('.message-list').evaluate(el=>{el.scrollTop=el.scrollHeight;el.dispatchEvent(new Event('scroll'))})
  const table='| 天数 | 景点 |\n| --- | --- |\n| 一 | 西湖 |'
  delta(extended+'\n\n'+table)
  await page.waitForTimeout(1300)
  check(await node.locator('table').count()===0,'An incomplete table was rendered')
  const final=extended+'\n\n'+table+'\n\n```js\nconst days = 3;\n```\n\n![像素测试图片](https://fixture.invalid/image.svg)'
  finish('SUCCEEDED',final)
  await page.getByRole('button',{name:'发送',exact:true}).waitFor()
  await settle(node)
  check(await node.locator('table').count()===1,'Completed table missing')
  check(await node.locator('pre code').innerText()==='const days = 3;\n','Code block split or corrupted')
  await node.locator('img').waitFor()
  await page.waitForTimeout(80)
  check(await page.locator('.message-list').evaluate(el=>el.scrollHeight-el.clientHeight-el.scrollTop)<75,'Bottom following stopped after typing/image layout')
  await image('completed-markdown')
  // Switching away and back displays the received snapshot immediately.
  await page.locator('.conversation-item').nth(1).click();await page.locator('.conversation-item').first().click()
  await page.locator('.message-row.assistant[data-turn-id] .markdown-body table').waitFor()
  check(await page.locator('.message-row.assistant[data-turn-id] .typewriter-message').getAttribute('data-typing')==='false','Restored conversation replayed')
  // Backend completion is independent of display completion; the durable cache contains the full reply.
  node=await send('success')
  await page.getByRole('button',{name:'发送',exact:true}).waitFor()
  check(await node.getAttribute('data-typing')==='true','Successful terminal skipped the display buffer')
  const cached=await page.evaluate(async()=>{const db=await new Promise(resolve=>{const req=indexedDB.open('travelscope-reconnect-v1');req.onsuccess=()=>resolve(req.result)});const result=await new Promise(resolve=>{const req=db.transaction('turns').objectStore('turns').getAll();req.onsuccess=()=>resolve(req.result)});db.close();return result.map(entry=>entry.view.snapshot.content)})
  check(cached.includes(initial+'最终结论。'),'Visual prefix was saved instead of the authoritative source')
  await settle(node)
  node=await send('rewrite');await settle(node)
  check((await node.innerText()).trim()==='服务器改写后的权威终稿。','Final rewrite appended to stale text')
  node=await send('failed');await settle(node)
  check((await node.innerText()).trim()==='生成失败时已确认的部分正文。','Failure did not flush display')
  // Explicit stop flushes even before its HTTP acknowledgment.
  node=await send('live')
  await page.getByRole('button',{name:'停止',exact:true}).waitFor()
  await page.waitForFunction(id=>document.querySelector(`.message-row.assistant[data-turn-id="${id}"] .typewriter-message`)?.dataset.typing==='true',active.turnId)
  await page.getByRole('button',{name:'停止',exact:true}).click()
  check(await node.getAttribute('data-typing')==='false','Stop kept animating while cancel was pending')
  check(await node.locator('.markdown-body').textContent()===initial,'Stop did not flush received source')
  releaseCancel();await page.getByText('取消后服务器确认的正文。',{exact:true}).waitFor()
  await page.emulateMedia({reducedMotion:'reduce'})
  node=await send('success');await settle(node)
  check((await node.innerText()).trim()===initial+'最终结论。','Reduced motion did not show complete source')
  await page.emulateMedia({reducedMotion:'no-preference'})
  check(errors.length===0,'Page errors: '+errors.join('; '))
  return {passed:true,checks:['single-character pacing without acceleration','cursor follows final character','atomic GFM table/code/image','replay without restarting','authoritative durable cache','independent terminal/display completion','final rewrite','failure and explicit stop','reduced motion','theme switch','scroll following and reader position'],screenshots:'output/typewriter'}
}
