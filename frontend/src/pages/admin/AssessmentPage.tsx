import { usePreferences } from '../../preferences/PreferencesProvider'
import { t, ui } from '../../preferences/i18n'
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import Overview from './assessment/Overview';
import Runs from './assessment/Runs';
import Batches, { CreateTest } from './assessment/Batches';
import Compare from './assessment/Compare';
import RunDetail from './assessment/RunDetail';
import { tabs } from './assessment/model';
import type { Tab } from './assessment/model';
import './AssessmentPage.css';
export default function AssessmentPage() {
    usePreferences();
    const [params, setParams] = useSearchParams();
    const [revision, setRevision] = useState(0);
    const [creating, setCreating] = useState(false);
    const requestedTab = params.get('tab');
    const tab: Tab = tabs.some(item => item.id === requestedTab) ? requestedTab as Tab : params.has('batchId') ? 'batches' : 'overview';
    const source = params.get('source') === 'ASSESSMENT' ? 'ASSESSMENT' : 'ONLINE';
    const mode = params.get('mode') === 'LIVE' ? 'LIVE' : 'FIXTURE';
    function update(patch: Record<string, string | null>) {
        setParams(previous => { const next = new URLSearchParams(previous); Object.entries(patch).forEach(([key, value]) => { if (value === null || value === '')
            next.delete(key);
        else
            next.set(key, value); }); return next; });
    }
    return <section className="assessment as-workspace">
    <header className="as-page-head"><div><div className="as-eyebrow">{t("TRAVELSCOPE / 运行与质量")}</div><h1>{t("评估中心")}</h1><p>{t("了解运行表现\uFF0C验证行程质量\uFF0C追溯每一次执行\u3002")}</p></div><div className="as-head-actions"><button onClick={() => setRevision(value => value + 1)}><span aria-hidden="true">↻</span>{t("刷新")}</button><button className="as-primary" onClick={() => setCreating(true)}><span aria-hidden="true">＋</span>{t("新建测试")}</button></div></header>
    <nav className="as-tabs" aria-label={t("评估中心导航")}>{ui(tabs.map(item => <button key={item.id} aria-current={tab === item.id ? 'page' : undefined} onClick={() => update({ tab: item.id, runId: null, sampleId: null })}>{ui(item.label)}</button>))}</nav>
    <div className="as-content">
      {ui(tab === 'overview' && <Overview source={source} mode={mode} revision={revision} onScope={(nextSource, nextMode) => update({ source: nextSource, mode: nextMode, page: null })} onInspect={component => update({ tab: "runs", source, mode, componentKind: component.kind, componentName: component.component_name, phase: component.phase, page: null, batchId: null, session: null, task: null, status: null, runId: null, sampleId: null })}/>)}
      {ui(tab === 'runs' && <Runs params={params} update={update} revision={revision}/>)}
      {ui(tab === 'batches' && <Batches params={params} update={update} revision={revision} onCreate={() => setCreating(true)}/>)}
      {ui(tab === 'compare' && <Compare params={params} update={update} revision={revision}/>)}
    </div>
    <footer className="as-page-foot">{t("统计范围以当前保留记录为准")}<span>{t("缺失数据不计为零 \u00B7 运行\u3001规则与评分分别呈现")}</span></footer>
    {ui(creating && <CreateTest onClose={() => setCreating(false)} onCreated={id => { setCreating(false); update({ tab: 'batches', batchId: id, runId: null, sampleId: null }); setRevision(value => value + 1); }}/>)}
    {ui((params.get('runId') || params.get('sampleId')) && !creating && <RunDetail id={params.get('runId')} sampleId={params.get('sampleId')} batchId={params.get('batchId')} revision={revision} onClose={() => update({ runId: null, sampleId: null, spanId: null, detailTab: null, traceFocus: null })} onRunChange={id => update({ runId: id, spanId: null })} detailTab={params.get("detailTab") === "trace" || params.get("detailTab") === "timeline" ? "trace" : params.get("detailTab") === "technical" ? "technical" : "summary"} spanId={params.get("spanId")} traceFocus={params.get("traceFocus")} onViewChange={detailTab => update({ detailTab })} onSpanChange={spanId => update({ spanId })}/>)}
  </section>;
}
