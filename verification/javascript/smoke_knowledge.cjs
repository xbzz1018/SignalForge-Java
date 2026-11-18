const {createRequire}=require('node:module');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../..');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const requireProject=createRequire(path.join(root,'frontend/package.json'));
const {chromium}=requireProject('playwright');
const {parse}=requireProject('dotenv');
const env=parse(fs.readFileSync(path.join(root,'frontend/.env')));
const base=process.env.SIGNALFORGE_WEB_URL||'http://127.0.0.1:58872';
const probeModel=process.argv.includes('--probe-model');
const report={checkedAt:new Date().toISOString(),browser:'Google Chrome',checks:{}};
const output=path.join(root,'.runtime/knowledge-verification');fs.mkdirSync(output,{recursive:true});

async function main(){
 let step='launch';const browser=await chromium.launch({channel:'chrome',headless:true,args:['--renderer-process-limit=2']});
 const context=await browser.newContext({viewport:{width:1440,height:1050},acceptDownloads:true});
 const page=await context.newPage();page.setDefaultTimeout(45000);
 const errors=[];page.on('pageerror',e=>errors.push(e.name));
 try{
  step='login';await page.goto(base+'/login',{waitUntil:'domcontentloaded'});
  await page.waitForFunction("Object.keys(document.querySelector('#identity')||{}).some(k=>k.startsWith('__reactProps$'))");
  await page.locator('#identity').fill(env.SIGNALFORGE_WEB_LOGIN);await page.locator('#password').fill(env.SIGNALFORGE_AUTH_ADMIN_PASSWORD);
  await page.locator('form button[type=submit]').click();await page.waitForURL(base+'/',{timeout:90000});
  report.checks.login=true;
  step='graph-page';await page.goto(base+'/knowledge-graph',{waitUntil:'domcontentloaded',timeout:90000});
  await page.getByRole('button',{name:'获取资料并构建图谱',exact:true}).waitFor();
  await page.getByRole('button',{name:'打开研究空间导航',exact:false}).click();
  assert.equal(await page.locator('a[href="/knowledge-graph"]').count(),1);
  await page.keyboard.press('Escape');report.checks.navigationEntry=true;
  step='submit';await page.locator('#security-code').fill('600519');
  const pending=page.waitForResponse(r=>r.url().endsWith('/knowledge/collections')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'获取资料并构建图谱',exact:true}).click();
  const submitted=await pending;assert.equal(submitted.status(),202);const job=await submitted.json();report.jobId=job.id;
  report.projectId=job.projectId;const root=base+'/api/java/projects/'+job.projectId+'/knowledge';
  step='collection';await page.getByRole('button',{name:'下载关系与证据',exact:true}).waitFor({timeout:150000});
  const finished=await (await context.request.get(root+'/collections/'+job.id)).json();assert.equal(finished.state,'SUCCEEDED');
  const response=await context.request.get(root+'/releases/'+finished.snapshotId);assert.equal(response.status(),200);
  const graph=await response.json();assert.equal(graph.storage,'NEO4J');assert.ok(graph.nodes.length>=2&&graph.edges.length>=1);
  assert.equal(graph.evidence.length,graph.edges.length);report.releaseId=graph.releaseId;report.nodes=graph.nodes.length;report.relationships=graph.edges.length;
  report.checks.liveCollectionAndNeo4jRead=true;
  step='evidence';await page.getByRole('button',{name:'关联公告',exact:false}).last().click();
  await page.getByText('关系证据',{exact:true}).waitFor();await page.getByText('查看保存的来源记录',{exact:true}).click();
  assert.ok(await page.locator('pre').count());report.checks.relationshipEvidence=true;
  step='source-hash';const source=await context.request.get(root+'/releases/'+graph.releaseId+'/source');assert.equal(source.status(),200);
  const raw=await source.body();assert.equal(crypto.createHash('sha256').update(raw).digest('hex'),graph.snapshotHash);report.checks.immutableSourceHash=true;
  step='download';const download=page.waitForEvent('download');await page.getByRole('button',{name:'下载来源快照并校验摘要',exact:true}).click();
  await (await download).saveAs(output+'/source.json');report.checks.sourceDownload=true;
  await page.screenshot({path:output+'/graph-desktop.png',fullPage:true});
  step='csrf';const csrf=await (await context.request.get(base+'/api/java/auth/csrf')).json();
  const headers={Origin:base,[csrf.headerName]:csrf.token,'Idempotency-Key':crypto.randomUUID()};
  assert.equal((await context.request.post(root+'/collections',{headers:{Origin:base},data:{securityId:'600519'}})).status(),403);report.checks.csrf=true;
  assert.equal((await context.request.get(base+'/api/java/projects/'+crypto.randomUUID()+'/knowledge/releases/'+graph.releaseId)).status(),403);report.checks.projectAuthorization=true;
  assert.equal((await context.request.post(root+'/cypher',{headers,data:{query:'RETURN 1'}})).status(),404);report.checks.noArbitraryCypher=true;
  step='repeat';const repeated=await context.request.post(root+'/collections',{headers,data:{securityId:'600519'}});assert.equal(repeated.status(),202);
  const next=await repeated.json();const same=await context.request.post(root+'/collections',{headers,data:{securityId:'600519'}});assert.equal(same.status(),200);assert.equal((await same.json()).id,next.id);
  assert.equal((await context.request.post(root+'/collections',{headers,data:{securityId:'SZ:000001'}})).status(),409);report.checks.idempotency=true;
  for(let i=0;i<45;i++){const state=await (await context.request.get(root+'/collections/'+next.id)).json();if(state.state==='SUCCEEDED'){assert.equal(state.snapshotId,graph.releaseId);assert.equal(state.reused,true);report.checks.snapshotReuse=true;break;}if(state.state==='FAILED')throw new Error('Repeat failed');await new Promise(r=>setTimeout(r,1000));}
  assert.equal(report.checks.snapshotReuse,true);
  if(probeModel){
  step='model';await page.getByRole('button',{name:'测试模型连接',exact:true}).click();
  await page.waitForFunction(()=>document.body.textContent.includes('连接成功，')||document.body.textContent.includes('连接失败：'),{},{timeout:25000});
  const model=await (await context.request.get(root+'/model')).json();report.model={configured:model.configured,connected:model.connected,model:model.model,errorCode:model.errorCode,totalTokens:model.totalTokens};
  report.checks.modelConnection=model.connected;
  }else report.modelProbe='skipped: pass --probe-model to send a billed fixed short prompt';
  step='reload';await page.reload({waitUntil:'domcontentloaded'});await page.getByRole('button',{name:'下载关系与证据',exact:true}).waitFor({timeout:45000});report.checks.reloadRestoresGraph=true;
  step='mobile';await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1));
  await page.screenshot({path:output+'/graph-mobile.png',fullPage:true});report.checks.mobileLayout=true;
  assert.deepEqual(errors,[]);report.checks.noPageErrors=true;
  report.status=Object.values(report.checks).every(Boolean)?'passed':'partial';
 }catch(e){report.status='failed';report.failedStep=step;report.errorType=e.name;if(e.name==='AssertionError')report.assertion={actual:e.actual,expected:e.expected};await page.screenshot({path:output+'/failure.png',fullPage:true}).catch(()=>{});process.exitCode=1;}
 finally{
  try{const csrf=await (await context.request.get(base+'/api/java/auth/csrf')).json();await context.request.post(base+'/api/java/auth/logout',{headers:{Origin:base,[csrf.headerName]:csrf.token}});await context.request.post(base+'/api/auth/sign-out',{headers:{Origin:base},data:{}});}catch{}
  await context.close();await browser.close();fs.writeFileSync(output+'/browser-report.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }
}
main().catch(e=>{console.log(JSON.stringify({status:'failed',errorType:e.name}));process.exitCode=1});
