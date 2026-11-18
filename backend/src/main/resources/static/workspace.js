const $ = (id) => document.getElementById(id);
const states = {QUEUED:'排队中',RUNNING:'获取数据与计算',VERIFYING:'验证结果',SUCCEEDED:'已完成',FAILED:'失败',CANCELLED:'已取消'};
const terminal = (state) => ['SUCCEEDED','FAILED','CANCELLED'].includes(state);
const model = {me:null,csrf:null,task:null,result:null,stream:null,pending:null,selection:0,fixture:false,config:{}};
const message = (text='') => { $('message').textContent=text; $('message').hidden=!text; };
const text = (tag,value,className) => { const node=document.createElement(tag); node.textContent=value; if(className)node.className=className; return node; };
function resetSession() { model.stream?.abort(); model.me=null; model.task=null; model.result=null; model.selection++; $('workspace').hidden=true; $('identity').hidden=true; $('login-panel').hidden=false; }
async function api(path, options={}) {
  const response=await fetch(path,{credentials:'same-origin',...options});
  if(!response.ok){ if(response.status===401 && path!=='/api/auth/login') resetSession(); const body=await response.json().catch(()=>({})); throw new Error(body.message||body.code||`请求失败 (${response.status})`); }
  return response.status===204?null:response.json();
}
async function csrf() { model.csrf=await api('/api/auth/csrf'); return {[model.csrf.headerName]:model.csrf.token}; }
const project = () => $('project').value;
const owner = () => model.me?.projects.find(p=>p.id===project())?.role==='OWNER';
const base = () => `/api/projects/${project()}/research/tasks`;
const rememberKey = () => `signalforge:last:${model.me.username}`;
function setDefaults(){const today=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()); const end=new Date(today+'T00:00:00Z'); end.setUTCDate(end.getUTCDate()-1); const start=new Date(end); start.setUTCDate(start.getUTCDate()-90); $('start').value=start.toISOString().slice(0,10); $('end').value=end.toISOString().slice(0,10); $('end').max=today; $('start').max=today;}
async function loadSession(){
  model.me=await api('/api/me'); model.config=await api('/api/workspace/config'); await csrf();
  $('username').textContent=model.me.username; $('identity').hidden=false; $('login-panel').hidden=true; $('workspace').hidden=false;
  $('project').replaceChildren(...model.me.projects.map(p=>{const option=text('option',`${p.name} · ${p.role==='OWNER'?'可编辑':'只读'}`);option.value=p.id;return option;}));
  let saved;try{saved=JSON.parse(localStorage.getItem(rememberKey()));}catch{}
  if(saved && model.me.projects.some(p=>p.id===saved.project)) $('project').value=saved.project;
  await projectChanged(saved?.task);
}
async function projectChanged(taskId){
  model.stream?.abort();model.selection++;model.task=null;model.result=null;model.pending=null;
  $('task-panel').hidden=true;$('result-panel').hidden=true;$('empty').hidden=false;
  $('role-note').hidden=owner();$('create').disabled=!owner();$('fixture').hidden=!owner()||!model.config.allowFixtures;
  if(!project()){ $('tasks').replaceChildren(text('p','当前账号尚未分配研究项目。','muted')); return; }
  await refreshTasks();if(taskId)await selectTask(taskId).catch(()=>{});
}
async function refreshTasks(){
  if(!project())return;const selectedProject=project();const tasks=await api(`${base()}?researchType=ETF_RISK_COMPARISON`);if(project()!==selectedProject)return;
  $('tasks').replaceChildren(...tasks.map(task=>{const item=text('button','',`task-item${task.id===model.task?.id?' selected':''}`);item.type='button'; item.dataset.taskId=task.id;item.append(text('strong',task.request.securities.map(s=>s.securityId).join(' / ')),text('span',`${states[task.state]||task.state} · ${new Date(task.createdAt).toLocaleString('zh-CN')}`));item.onclick=()=>selectTask(task.id).catch(e=>message(e.message));return item;}));
  if(!tasks.length)$('tasks').append(text('p','还没有研究任务。','muted small'));
}
function renderTask(task){
  model.task=task;$('empty').hidden=true;$('task-panel').hidden=false;
  $('task-title').textContent=task.request.securities.map(s=>s.securityId).join(' / ');
  $('task-state').textContent=states[task.state]||task.state;$('task-state').className=`tag${task.state==='FAILED'?' failed':''}`;
  $('task-detail').textContent=`${task.request.priceRange.start} 至 ${task.request.priceRange.end} · 第 ${task.attempts} 次执行`;
  $('task-error').hidden=!task.errorCode;$('task-error').textContent=task.errorCode?`任务未完成：${task.errorCode}。请检查行情服务或数据区间后重新提交。`:'';
  $('cancel').hidden=!owner()||terminal(task.state);
}
function showEvent(event){
  $('events').append(text('li',`${event.sequence}. ${states[event.type]||event.type}`));
  if(states[event.type]){
    const position=['QUEUED','RUNNING','VERIFYING','SUCCEEDED'];
    const current=position.indexOf(model.task.state),next=position.indexOf(event.type);
    if(!terminal(model.task.state) && (next>=current || ['FAILED','CANCELLED'].includes(event.type))){
      renderTask({...model.task,state:event.type,eventSequence:event.sequence,
        attempts:event.payload?.attempt??model.task.attempts,errorCode:event.payload?.code??model.task.errorCode});
    }
  }
}
async function selectTask(id){
  model.stream?.abort();const selection=++model.selection;const path=base()+'/'+id;
  model.result=null;$('result-panel').hidden=true;$('download').hidden=true;$('events').replaceChildren();message();
  const task=await api(path);if(selection!==model.selection)return;
  if(task.request.researchType!=='ETF_RISK_COMPARISON')return;
  renderTask(task);localStorage.setItem(rememberKey(),JSON.stringify({project:project(),task:id}));
  await refreshTasks();if(selection!==model.selection)return;
  const controller=new AbortController();model.stream=controller;
  watch(path,task,controller,selection).catch(e=>{if(e.name!=='AbortError'&&selection===model.selection)message(e.message);});
}
async function watch(path,initial,controller,selection){
  let cursor=0, task=initial;
  while(!controller.signal.aborted && selection===model.selection){
    if(task.state==='SUCCEEDED'){const result=await api(path+'/result');if(selection!==model.selection)return;renderResult(result);}
    const response=await fetch(path+'/events',{headers:{'Last-Event-ID':String(cursor)},signal:controller.signal,credentials:'same-origin'});
    if(response.status===401){resetSession();throw new Error('会话已过期，请重新登录。');}
    if(!response.ok)throw new Error(`进度连接失败 (${response.status})`);
    const reader=response.body.getReader(), decoder=new TextDecoder();let buffer='';
    try{
      while(true){const {done,value}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true}).replace(/\r\n/g,'\n');let end;
        while((end=buffer.indexOf('\n\n'))>=0){const frame=buffer.slice(0,end);buffer=buffer.slice(end+2);const lines=frame.split('\n');const data=lines.filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');if(!data)continue;const event=JSON.parse(data);if(event.sequence<=cursor)continue;cursor=event.sequence;if(selection!==model.selection)return;showEvent(event);}
      }
    }catch(error){if(error.name==='AbortError')throw error;}finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
    task=await api(path);if(selection!==model.selection)return;renderTask(task);await refreshTasks();
    if(terminal(task.state)){if(task.state==='SUCCEEDED'){const result=await api(path+'/result');if(selection!==model.selection)return;renderResult(result);}return;}
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
}
function percent(value){return Number.isFinite(Number(value))?`${Number(value).toFixed(2)}%`:'—';}
function renderResult(result){
  model.result=result;$('result-panel').hidden=false;$('download').hidden=false;
  $('data-kind').textContent=result.demoData?'合成演示数据':'真实行情';
  $('metrics').replaceChildren(...result.series.map(s=>{const row=document.createElement('tr');row.append(text('td',s.securityId),...['TOTAL_RETURN','ANNUALIZED_VOLATILITY','MAX_DRAWDOWN'].map(k=>text('td',percent(s.metrics[k]))));return row;}));
  const statuses={SNAPSHOT_REUSED:'复用已保存快照',PROVIDER_FETCH:'本次自动获取',SYNTHETIC:'合成演示'};
  $('sources').replaceChildren(...result.series.map(s=>{const row=text('div','','source-row');row.append(text('strong',s.securityId),text('p',`${statuses[s.fetchStatus]||'已保存输入'} · ${s.coverage.observations} 个样本 · ${s.coverage.firstDate} 至 ${s.coverage.lastDate}`,'muted small'));for(const ref of s.sourceRefs)row.append(text('p',`来源：${ref.provider} · 获取于 ${new Date(ref.fetchedAt).toLocaleString('zh-CN')}`,'muted small'));const details=document.createElement('details');details.append(text('summary','查看来源标识与数据版本'),text('pre',JSON.stringify({dataVersion:s.dataVersion,sourceRefs:s.sourceRefs,coverage:s.coverage},null,2)));row.append(details);return row;}));
  for(const id of ['return-legend','drawdown-legend'])$(id).replaceChildren(...result.series.map(s=>text('span',s.securityId)));
  $('chart-range').textContent=`样本范围：${result.series.map(s=>`${s.securityId} ${s.coverage.firstDate}—${s.coverage.lastDate}`).join('；')}`;drawCharts();
}
function drawCharts(){
  if(!model.result)return;
  for(const [id,drawdown] of [['returns-chart',false],['drawdown-chart',true]]){
    const canvas=$(id),width=canvas.clientWidth,height=220;if(!width)return;const scale=window.devicePixelRatio||1;canvas.width=width*scale;canvas.height=height*scale;const ctx=canvas.getContext('2d');ctx.scale(scale,scale);
    const series=model.result.series.map(s=>{let peak=Number(s.bars[0].close);return s.bars.map(b=>{const price=Number(b.close);peak=Math.max(peak,price);return {day:Date.parse(b.date),value:drawdown?(peak-price)/peak*100:(price/Number(s.bars[0].close)-1)*100};});});
    const points=series.flat(),minDay=Math.min(...points.map(p=>p.day)),maxDay=Math.max(...points.map(p=>p.day));let low=Math.min(0,...points.map(p=>p.value)),high=Math.max(0,...points.map(p=>p.value));if(high===low)high=low+1;
    const left=47,right=12,top=16,bottom=24,w=width-left-right,h=height-top-bottom;ctx.font='10px system-ui';ctx.fillStyle='#64748b';ctx.strokeStyle='#edf1f5';
    for(let i=0;i<=4;i++){const y=top+h*i/4;ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(width-right,y);ctx.stroke();ctx.fillText(`${(high-(high-low)*i/4).toFixed(1)}%`,0,y+4);}
    series.forEach((rows,index)=>{ctx.strokeStyle=['#15846d','#5578b9'][index];ctx.lineWidth=2;ctx.beginPath();rows.forEach((p,i)=>{const x=left+(p.day-minDay)/(maxDay-minDay||1)*w,y=top+(high-p.value)/(high-low)*h;if(i)ctx.lineTo(x,y);else ctx.moveTo(x,y);});ctx.stroke();});
  }
}
$('login-form').onsubmit=async event=>{event.preventDefault();message();$('login-submit').disabled=true;try{const headers=await csrf();headers['Content-Type']='application/x-www-form-urlencoded';await api('/api/auth/login',{method:'POST',headers,body:new URLSearchParams({username:$('login-user').value,password:$('login-password').value})});$('login-password').value='';await loadSession();}catch(e){message(e.message);}finally{$('login-submit').disabled=false;}};
$('logout').onclick=async()=>{try{await api('/api/auth/logout',{method:'POST',headers:await csrf()});resetSession();message();}catch(e){message(e.message);}};
$('project').onchange=()=>projectChanged().catch(e=>message(e.message));$('refresh').onclick=()=>refreshTasks().catch(e=>message(e.message));
$('research-form').oninput=()=>{model.pending=null;};
$('fixture').onclick=()=>{model.fixture=true;model.pending=null;$('security-a').value='fixture:ETF_A';$('security-b').value='fixture:ETF_B';$('start').value='2025-01-02';$('end').value='2025-01-09';};
$('research-form').onsubmit=async event=>{
  event.preventDefault();if(!owner())return;message();$('create').disabled=true;
  try{
    if(!model.pending){const codes=[$('security-a').value.trim(),$('security-b').value.trim()];const demo=codes.every(code=>/^fixture:ETF_[AB]$/.test(code));if(new Set(codes).size!==2)throw new Error('请选择两只不同的 ETF。');if(!demo&&!codes.every(code=>/^[15][0-9]{5}$/.test(code)))throw new Error('请输入沪深市场的六位 ETF 代码。');if(demo&&!model.config.allowFixtures)throw new Error('当前服务未启用演示数据。');const start=$('start').value,end=$('end').value;if(start>end||(Date.parse(end)-Date.parse(start))/86400000>730)throw new Error('日期区间无效或超过 730 天。');
      model.pending={key:crypto.randomUUID(),request:{schemaVersion:'1.0',researchType:'ETF_RISK_COMPARISON',securities:codes.map(code=>({securityId:code,market:demo?'FIXTURE':code.startsWith('5')?'SSE':'SZSE'})),knowledgeAsOf:new Date().toISOString(),priceRange:{start,end,frequency:'DAILY'},priceAdjustment:'NONE',metrics:['TOTAL_RETURN','ANNUALIZED_VOLATILITY','MAX_DRAWDOWN'],executionMode:'DETERMINISTIC'}};
    }
    const task=await api(base(),{method:'POST',headers:{...await csrf(),'Content-Type':'application/json','Idempotency-Key':model.pending.key},body:JSON.stringify(model.pending.request)});model.pending=null;await selectTask(task.id);
  }catch(e){message(e.message);}finally{$('create').disabled=!owner();}
};
$('cancel').onclick=async()=>{const task=model.task;if(!task||!owner())return;try{const updated=await api(`${base()}/${task.id}/cancel`,{method:'POST',headers:await csrf()});if(model.task?.id===updated.id)renderTask(updated);await refreshTasks();}catch(e){message(e.message);}};
$('download').onclick=()=>{if(!model.result)return;const url=URL.createObjectURL(new Blob([JSON.stringify(model.result,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download=`signalforge-${model.task.id}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
window.addEventListener('resize',drawCharts);setDefaults();loadSession().catch(e=>{if(!e.message.includes('UNAUTHENTICATED'))message(e.message);});
