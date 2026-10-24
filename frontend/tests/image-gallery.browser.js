// Real chat UI with repeatable image responses; no real account/model/CDN traffic.
/* eslint-disable no-unused-expressions */
async page => {
  const base='http://127.0.0.1:5173'
  const urls=[
    'https://store.is.autonavi.com/showpic/78e3e7b400e9290d0000003670488183',
    'https://store.is.autonavi.com/showpic/69cf8cf1d42c80510000003313168566?type=pic',
    'https://store.is.autonavi.com/showpic/58fbc3bf5460862ef2056ae1aa053754',
  ]
  const names=['杭州西湖风景名胜区','曲院风荷-绉云峰','雷峰塔景区']
  const photos=urls.map((url,i)=>`![${names[i]}](${url})`).join(' ')
  const history=[
    {id:1,role:'user',content:'想看看曲院风荷附近的照片。'},
    {id:2,role:'assistant',content:'为您找到了曲院风荷及周边的风景照片。\n\n📷 相关图片\n\n'+photos},
    {id:3,role:'assistant',content:`只有一张图片。\n\n![单张图片](${urls[0]})`},
    {id:4,role:'assistant',content:`![重复一](${urls[0]}) ![重复二](${urls[0]})`},
    {id:5,role:'assistant',content:'![失败图片](https://fixture.invalid/broken.jpg)'},
  ]
  const errors=[],imageRequests=[];page.on('pageerror',error=>errors.push(error.message))
  const check=(ok,message)=>{if(!ok)throw Error(message)}
  let broken=true,active;const logs=[];const turns=[]
  const svg=index=>`<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900"><path fill="${['#bde0fe','#ffe5d9','#cdb4db'][index]}" d="M0 0h1600v900H0z"/><path fill="#a8dcd9" d="M0 520h1600v380H0z"/><path fill="#60435f" d="m0 550 330-360 320 360 300-260 330 260 320-220v570H0z"/><path fill="#d8e2dc" d="m0 670 500-200 400 200 300-140 400 180v190H0z"/><text x="80" y="130" font-size="64" fill="#493953">${names[index]} · TEST IMAGE</text></svg>`
  await page.context().route('https://store.is.autonavi.com/**',route=>{
    imageRequests.push(route.request())
    return route.fulfill({contentType:'image/svg+xml',body:svg(Math.max(0,urls.indexOf(route.request().url())))})
  })
  await page.context().route('https://fixture.invalid/**',route=>broken?route.fulfill({status:503,body:'fixture failure'}):route.fulfill({contentType:'image/svg+xml',body:svg(1)}))
  const delta=text=>{logs.push({turnId:active.turnId,seq:logs.length+1,type:'delta',payload:text.slice(active.snapshot.content.length)});active.snapshot={content:text};active.lastSeq=logs.length;active.snapshotSeq=logs.length}
  await page.context().route('**/api/**',async route=>{
    const req=route.request(),url=new URL(req.url()),path=url.pathname
    if(!path.startsWith('/api/'))return route.continue()
    let body
    if(path==='/api/auth/me')body={userId:73,role:'user',nickname:'图集验收'}
    else if(path==='/api/chat/conversations')body=[{id:1,title:'图片浏览',updatedAt:'2026-10-24'},{id:2,title:'另一个会话',updatedAt:'2026-10-24'}]
    else if(path.endsWith('/messages'))body=path.includes('/1/')?history:[]
    else if(path.endsWith('/turns')&&path.includes('/conversations/'))body=path.includes('/1/')?turns:[]
    else if(path==='/api/chat/turns'&&req.method()==='POST'){
      const input=req.postDataJSON();active={turnId:input.turnId,conversationId:1,status:'RUNNING',inputText:input.message,snapshot:{content:''},snapshotSeq:0,lastSeq:0,userMessageId:10,assistantMessageId:null,createdAt:new Date().toISOString(),outcome:null};turns.push(active);body=active
    }else if(path.endsWith('/events')){
      if(!logs.length)delta(`新增图片：\n\n![流式图片一](${urls[0]})`)
      const events=logs.filter(event=>event.seq>Number(url.searchParams.get('afterSeq')))
      return route.fulfill({contentType:'text/event-stream',body:events.map(event=>`id: ${event.seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join('')||': heartbeat\n\n'})
    }else if(path.endsWith('/cancel')){active.status='CANCELLED';body=active}
    else if(path.startsWith('/api/chat/turns/'))body=active
    else return route.fulfill({status:404,json:{message:'Unknown fixture API'}})
    return route.fulfill({status:200,json:body})
  })
  await page.goto(base+'/login')
  await page.evaluate(async()=>{localStorage.clear();await new Promise(resolve=>{const req=indexedDB.deleteDatabase('travelscope-reconnect-v1');req.onsuccess=req.onerror=req.onblocked=resolve});localStorage.setItem('travelscope_token','image-gallery-mock');localStorage.setItem('travelscope_user_id','73');localStorage.setItem('travelscope_role','user')})
  await page.setViewportSize({width:1200,height:900});await page.goto(base+'/')
  const row=id=>page.locator(`.message-row[data-message-id="${id}"]`)
  const thumbnail=(id,index=0)=>row(id).locator('.gallery-thumbnail').nth(index)
  const viewer=()=>page.getByRole('dialog',{name:'图片预览',exact:true})
  const full=()=>viewer().locator('.gallery-full-image')
  const position=()=>viewer().locator('.gallery-position')
  const close=()=>viewer().getByRole('button',{name:'关闭图片预览',exact:true}).click()
  async function loaded(){await page.waitForFunction(()=>{const img=document.querySelector('.gallery-full-image');return img?.complete&&img.naturalWidth>0})}
  await thumbnail(2).waitFor()
  check(await row(2).locator('.gallery-thumbnail').count()===3,'Markdown image group incomplete')
  check(await row(2).locator('.gallery-thumbnail:visible').count()===2,'More than two thumbnails are visible')
  check(await thumbnail(2,1).locator('.gallery-more').textContent()==='+1','Second preview has no remaining image count')
  check(await page.locator('dialog').count()===0,'Large viewer opened before a click')
  const input=page.locator('.input-area textarea');await input.fill('保留输入草稿')
  await thumbnail(2,1).click();await viewer().waitFor();await loaded()
  check(await full().getAttribute('src')===urls[1],'Clicked thumbnail opened the wrong image or rewrote the CDN URL')
  check(await position().textContent()==='第 2 / 3 张','Viewer mixed images from different replies')
  check(await viewer().locator('.gallery-full-image').count()===1,'Viewer displays multiple large images')
  check(await full().getAttribute('referrerpolicy')==='no-referrer','Large image lost hotlink policy')
  const large=await full().boundingBox(),small=await thumbnail(2,1).boundingBox();check(large.width>small.width&&large.height>small.height,'Full image is not larger than the thumbnail')
  await viewer().getByRole('button',{name:'下一张图片',exact:true}).click();check(await position().textContent()==='第 3 / 3 张','Next did not advance')
  await viewer().getByRole('button',{name:'下一张图片',exact:true}).click();check(await full().getAttribute('src')===urls[0],'Last image did not wrap to first')
  await page.keyboard.press('ArrowLeft');check(await full().getAttribute('src')===urls[2],'First image did not wrap to last')
  await page.keyboard.press('ArrowRight');check(await full().getAttribute('src')===urls[0],'Keyboard next did not advance')
  for(let i=0;i<6;i++){await page.keyboard.press('Tab');check(await viewer().evaluate(el=>el.contains(document.activeElement)),'Focus escaped the modal')}
  await page.screenshot({path:'output/image-gallery/large-light.png'})
  await page.keyboard.press('Escape');await viewer().waitFor({state:'detached'})
  check(await thumbnail(2,1).evaluate(el=>el===document.activeElement),'Close did not return focus to the clicked thumbnail')
  check(await input.inputValue()==='保留输入草稿','Gallery interaction cleared input')
  check(await page.evaluate(()=>document.body.style.overflow)!=='hidden','Modal leaked scroll lock')
  await thumbnail(2).click();await page.mouse.click(3,3);await viewer().waitFor({state:'detached'})
  await thumbnail(3).click();check(await position().textContent()==='第 1 / 1 张','Single image gallery included other replies')
  check(await viewer().getByRole('button',{name:'下一张图片',exact:true}).isDisabled(),'Single image has an active next button')
  await page.keyboard.press('ArrowRight');check(await position().textContent()==='第 1 / 1 张','Single image keyboard navigation failed');await close()
  await thumbnail(4,1).click();check(await position().textContent()==='第 2 / 2 张','Repeated URLs lost their individual positions');await close()
  await thumbnail(5).scrollIntoViewIfNeeded();await thumbnail(5).getByText('预览加载失败，点击重试',{exact:true}).waitFor()
  await thumbnail(5).click();await viewer().getByText('图片加载失败，请重试或切换其他图片。',{exact:true}).waitFor()
  broken=false;await viewer().getByRole('button',{name:'重试',exact:true}).click();await loaded();await close()
  for(const [theme,name] of [['dark','深色'],['peach','水蜜桃'],['blue-bowl','蓝白碗']]){
    await page.getByRole('button',{name:/^主题：/}).click();await page.getByRole('menuitemradio',{name,exact:true}).click()
    await thumbnail(2).click();await loaded();check(await page.locator('html').getAttribute('data-theme')===theme,'Gallery theme did not follow the app')
    await page.screenshot({path:`output/image-gallery/large-${theme}.png`});await close()
  }
  await page.setViewportSize({width:430,height:850});await thumbnail(2).scrollIntoViewIfNeeded()
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'Thumbnails overflow narrow layout')
  await page.screenshot({path:'output/image-gallery/previews-narrow.png'})
  await thumbnail(2).click();await loaded()
  const bounds=await viewer().boundingBox();check(bounds.x>=0&&bounds.y>=0&&bounds.x+bounds.width<=430&&bounds.y+bounds.height<=850,'Viewer does not fit a narrow viewport')
  await page.screenshot({path:'output/image-gallery/large-narrow.png'});await close()
  await page.setViewportSize({width:1200,height:900});await page.getByRole('button',{name:'切换到英文',exact:true}).click()
  await row(2).getByRole('button',{name:`View image: ${names[0]}`,exact:true}).click()
  await page.getByRole('dialog',{name:'Image preview',exact:true}).getByRole('button',{name:'Next image',exact:true}).click()
  check(await page.locator('.gallery-position').textContent()==='Image 2 / 3','English gallery labels missing')
  await page.getByRole('button',{name:'Close image preview',exact:true}).click()
  await page.getByRole('button',{name:'Switch to Chinese',exact:true}).click()
  // The live reply uses the existing typewriter; newly rendered images join the open gallery.
  await input.fill('流式图集');await page.getByRole('button',{name:'发送',exact:true}).click()
  const live=page.locator('.message-row.assistant[data-turn-id]')
  await live.locator('.gallery-thumbnail').first().click();check(await position().textContent()==='第 1 / 1 张','Live first image missing')
  delta(active.snapshot.content+`\n\n![流式图片二](${urls[1]})`)
  await page.waitForFunction(()=>document.querySelector('.gallery-position')?.textContent==='第 1 / 2 张')
  await viewer().getByRole('button',{name:'下一张图片',exact:true}).click();check(await full().getAttribute('src')===urls[1],'Open gallery did not pick up the streamed image')
  delta(active.snapshot.content+`\n\n![流式图片三](${urls[2]})`)
  await page.waitForFunction(()=>document.querySelector('.gallery-position')?.textContent==='第 2 / 3 张')
  check(await live.locator('.gallery-thumbnail:visible').count()===2,'Streamed images exceeded the preview limit')
  check(await live.locator('.gallery-more').textContent()==='+1','Streamed remainder badge missing')
  await viewer().getByRole('button',{name:'下一张图片',exact:true}).click();check(await full().getAttribute('src')===urls[2],'Hidden preview was omitted from the viewer')
  await close();await page.getByRole('button',{name:'停止',exact:true}).click();await page.getByRole('button',{name:'发送',exact:true}).waitFor()
  // Modal close must leave message operations working and preserve the source Markdown.
  await row(2).getByRole('button',{name:'回复',exact:true}).click();check(await page.locator('.reply-quote-text').textContent()===history[1].content,'Viewer mutated the original message used by Reply')
  await page.getByRole('button',{name:'取消引用',exact:true}).click()
  await thumbnail(2).click()
  // Navigate programmatically to model a conversation switch while the native modal is open.
  await page.locator('.conversation-item').nth(1).evaluate(el=>el.click())
  await page.locator('dialog').waitFor({state:'detached'});check(await page.evaluate(()=>document.body.style.overflow)!=='hidden','Conversation switch left the modal locked')
  // Separate Markdown fragments share one scope; Escape closes only the top modal.
  await page.evaluate(async urls=>{const {mountNestedGallery}=await import('/tests/image-gallery-nested.tsx');mountNestedGallery(urls)},[urls[0],urls[2]])
  const drawer=page.getByRole('dialog',{name:'图集嵌套验收',exact:true})
  await drawer.getByRole('button',{name:'查看图片：嵌套图片一',exact:true}).click()
  check(await position().textContent()==='第 1 / 2 张','Nested Markdown fragments did not share the gallery')
  await page.keyboard.press('ArrowRight');check(await full().getAttribute('src')===urls[2],'Nested image order incorrect')
  await page.keyboard.press('Escape');await viewer().waitFor({state:'detached'});check(await drawer.isVisible(),'Escape closed both viewer and underlying drawer')
  check(await page.evaluate(()=>document.body.style.overflow)==='hidden','Image close removed the underlying drawer scroll lock')
  await page.keyboard.press('Escape');await drawer.waitFor({state:'detached'})
  check(await page.evaluate(()=>document.body.style.overflow)!=='hidden','Nested drawer cleanup left a scroll lock')
  await page.evaluate(async()=>{const {unmountNestedGallery}=await import('/tests/image-gallery-nested.tsx');unmountNestedGallery()})
  check(imageRequests.every(req=>!req.headers().referer),'CDN previews sent a referrer')
  check(errors.length===0,'Page errors: '+errors.join('; '))
  return {passed:true,checks:['clicked image and per-reply order','single large image','circular buttons and keyboard','single and duplicate images','Escape/backdrop and focus trap/restore','failed preview/detail retry','unchanged CDN URLs and referrer policy','four themes, English and narrow screens','live appended images','reply source and conversation cleanup','nested fragments and assessment drawer keyboard/scroll isolation'],screenshots:'output/image-gallery'}
}
