// Development-only fixture: no API, authentication, model calls or production route.
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '../src/index.css'
import '../src/App.css'
import '../src/preferences/styles.css'
import { PreferencesProvider, usePreferences } from '../src/preferences/PreferencesProvider'
import SidebarTools from '../src/components/SidebarTools'
import RequirementFormCard from '../src/components/RequirementFormCard'
import type { RequirementFormPayload } from '../src/types'

const payload: RequirementFormPayload={fields:[{name:'destination',label:'目的地',labelKey:'requirements.destination.label',placeholder:'您想去哪个城市？',placeholderKey:'requirements.destination.placeholder',type:'text',value:'杭州',filled:true,required:true,options:[]},{name:'preference',label:'偏好',labelKey:'requirements.preference.label',placeholder:'可多选，也可补充描述',type:'multi-select',value:'',filled:false,required:false,options:['自然风景','美食'],optionKeys:{'自然风景':'requirements.preference.option.0','美食':'requirements.preference.option.2'}}],hint:null,errors:[]}
function Fixture() {
  usePreferences()
  const [submitted,setSubmitted]=useState('')
  return <div className="app"><aside className="sidebar"><div className="conversation-list">{Array.from({length:35},(_,i)=><div className="conversation-item" key={i}>Conversation {i+1}</div>)}</div><SidebarTools /></aside><main className="chat-area"><header className="chat-header">Appearance regression fixture</header><div className="message-list"><RequirementFormCard payload={payload} submitted={false} disabled={false} onAction={(_,values)=>setSubmitted(JSON.stringify(values))}/><output aria-label="Submitted original values">{submitted}</output></div></main></div>
}
createRoot(document.getElementById('root')!).render(<PreferencesProvider><Fixture /></PreferencesProvider>)
