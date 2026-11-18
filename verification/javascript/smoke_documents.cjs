const {createRequire}=require('node:module');
const path=require('node:path');
const projectRoot=path.resolve(__dirname,'../..');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const local=createRequire(path.join(projectRoot,'frontend/package.json'));
const {chromium}=local('playwright');
const env=local('dotenv').parse(fs.readFileSync(path.join(projectRoot,'frontend/.env')));
const base=process.env.SIGNALFORGE_WEB_URL||env.BETTER_AUTH_URL||'http://127.0.0.1:58872';
const output=path.join(projectRoot,'.runtime/document-verification');fs.mkdirSync(output,{recursive:true});
const publishing=process.argv.includes('--verify-publication');
const extracting=process.argv.includes('--extract');
const previous=publishing?JSON.parse(fs.readFileSync(output+'/extraction-report.json')):null;
const report={checkedAt:new Date().toISOString(),browser:'Google Chrome',checks:{}};

async function main(){
 let step='launch';const browser=await chromium.launch({channel:'chrome',headless:true,args:['--renderer-process-limit=2']});
 const context=await browser.newContext({viewport:{width:1440,height:1050},acceptDownloads:true});
 const page=await context.newPage();page.setDefaultTimeout(45000);const errors=[];page.on('pageerror',e=>errors.push(e.name));
 try{
  step='login';await page.goto(base+'/login',{waitUntil:'domcontentloaded'});
  await page.waitForFunction("Object.keys(document.querySelector('#identity')||{}).some(k=>k.startsWith('__reactProps$'))");
  await page.locator('#identity').fill(env.SIGNALFORGE_WEB_LOGIN);await page.locator('#password').fill(env.SIGNALFORGE_AUTH_ADMIN_PASSWORD);
  await page.locator('form button[type=submit]').click();await page.waitForURL(base+'/',{timeout:90000});
  await page.goto(base+'/knowledge-graph',{waitUntil:'domcontentloaded',timeout:90000});
  await page.waitForFunction(()=>document.body.textContent.includes('连接研究会话')||document.querySelector('#security-code'));
  if(await page.locator('#research-password').count()){
   await page.locator('#research-password').fill(env.SIGNALFORGE_AUTH_ADMIN_PASSWORD);
   await page.getByRole('button',{name:'连接研究服务',exact:true}).click();
  }
  await page.locator('#security-code').waitFor();report.checks.loginAndResearchSession=true;
  const me=await(await context.request.get(base+'/api/java/me')).json();const project=publishing?previous.projectId:me.projects.find(p=>p.role==='OWNER').id;
  report.projectId=project;const root=base+'/api/java/projects/'+project+'/knowledge';
  async function post(path,data,key=crypto.randomUUID()){
   const csrf=await(await context.request.get(base+'/api/java/auth/csrf')).json();
   return context.request.post(root+path,{headers:{Origin:base,[csrf.headerName]:csrf.token,'Idempotency-Key':key},data});
  }
  async function wait(path){
   for(let i=0;i<160;i++){
    const response=await context.request.get(root+path);assert.equal(response.status(),200);const job=await response.json();
    if(job.state==='SUCCEEDED')return job;
    if(['FAILED','CANCELLED'].includes(job.state))throw new Error(job.errorCode||job.state);
    await new Promise(resolve=>setTimeout(resolve,1000));
   }
   throw new Error('TASK_TIMEOUT');
  }
  if(!publishing){
   step='collect';await page.locator('#security-code').fill('600519');
   const submitted=page.waitForResponse(r=>r.url()===root+'/collections'&&r.request().method()==='POST');
   await page.getByRole('button',{name:'获取资料并构建图谱',exact:true}).click();
   const response=await submitted;assert.ok([200,202].includes(response.status()));const created=await response.json();
   const job=await wait('/collections/'+created.id);report.catalogId=job.snapshotId;report.collectionId=job.id;
   await page.locator('#notice-picker').waitFor({timeout:90000});
   const source=await(await context.request.get(root+'/releases/'+report.catalogId+'/source')).json();
   const notice=source.announcements.find(n=>n.providerId==='AN202611021831320924')||source.announcements.find(n=>/业绩说明会/.test(n.title))||source.announcements[0];
   assert.ok(notice);report.providerId=notice.providerId;report.title=notice.title;
   step='document';await page.locator('#notice-picker').selectOption(notice.providerId);
   const docSubmitted=page.waitForResponse(r=>r.url()===root+'/documents'&&r.request().method()==='POST');
   await page.getByRole('button',{name:'抓取所选公告正文',exact:true}).click();
   const docResponse=await docSubmitted;assert.ok([200,202].includes(docResponse.status()));const docJob=await wait('/evidence/jobs/'+(await docResponse.json()).id);
   report.documentId=docJob.resultId;
   const document=await(await context.request.get(root+'/documents/'+report.documentId)).json();
   report.pdfHash=document.pdfHash;report.coverage=document.parsed.coverage;report.chunks=document.parsed.chunks.length;
   assert.ok(document.parsed.chunks.length>0);report.checks.livePdfDownloadAndParse=true;
   const pdf=await context.request.get(root+'/documents/'+document.id+'/pdf');assert.equal(pdf.status(),200);
   assert.ok(pdf.headers()['content-disposition'].startsWith('attachment;'));assert.equal(crypto.createHash('sha256').update(await pdf.body()).digest('hex'),document.pdfHash);
   report.checks.storedPdfHashAndDownloadHeaders=true;
   await page.getByLabel('正文版本',{exact:true}).selectOption(document.id);
   await page.getByText('下载保存的原始 PDF',{exact:true}).waitFor();
   const download=page.waitForEvent('download');await page.getByText('下载保存的原始 PDF',{exact:true}).click();await(await download).saveAs(output+'/announcement.pdf');
   assert.equal(crypto.createHash('sha256').update(fs.readFileSync(output+'/announcement.pdf')).digest('hex'),document.pdfHash);report.checks.browserPdfDownload=true;
   assert.equal((await context.request.get(base+'/api/java/projects/'+crypto.randomUUID()+'/knowledge/documents/'+document.id)).status(),403);report.checks.projectAuthorization=true;
   assert.equal((await context.request.post(root+'/documents',{headers:{Origin:base},data:{catalogSnapshotId:report.catalogId,providerId:notice.providerId}})).status(),403);report.checks.csrf=true;
   step='extraction';if(extracting){
    const submitted=page.waitForResponse(r=>r.url()===root+'/documents/'+document.id+'/extractions'&&r.request().method()==='POST');
    await page.getByRole('button',{name:'使用 DeepSeek 提出候选',exact:true}).click();
    const response=await submitted;assert.ok([200,202].includes(response.status()));const initial=await response.json();
    const job=await wait('/evidence/jobs/'+initial.id);report.extractionJobId=job.id;
    const runs=await(await context.request.get(root+'/documents/'+document.id+'/extractions')).json();report.run=runs.find(run=>run.jobId===job.id);
    assert.ok(report.run.actualTokens>0);report.checks.realDeepSeekUsageRecorded=true;
    report.candidates=await(await context.request.get(root+'/documents/'+document.id+'/candidates')).json();
    for(const candidate of report.candidates.filter(c=>c.status!=='INVALID')){
     const chunk=document.parsed.chunks.find(c=>c.id===candidate.chunkId);assert.ok(chunk);
     assert.equal(chunk.text.slice(candidate.quoteStart,candidate.quoteEnd),candidate.quote);
     assert.ok(candidate.quote.includes(candidate.object));
     if(/将于|计划|拟/.test(candidate.quote))assert.notEqual(candidate.modality,'ASSERTED');
    }
    report.checks.literalCitationsAndPlanSemantics=true;
    const valid=report.candidates.find(c=>c.status==='PENDING')||report.candidates.find(c=>['APPROVED','PUBLISHED'].includes(c.status));assert.ok(valid);
    if(valid.status==='PENDING'){assert.equal((await post('/publications',{catalogSnapshotId:report.catalogId,candidateIds:[valid.id]})).status(),409);report.checks.unreviewedCannotPublish=true;}else{report.checks.reviewedCandidateReuse=true;}
    const repeated=await post('/documents/'+document.id+'/extractions',{retryFailed:false});assert.equal(repeated.status(),200);assert.equal((await repeated.json()).id,job.id);report.checks.extractionInputReuse=true;
    await page.locator(`[data-candidate-id="${valid.id}"]`).waitFor({timeout:20000});
    await page.locator(`[data-candidate-id="${valid.id}"]`).getByRole('button',{name:'定位原文',exact:false}).click();
    await page.locator('[aria-label="原文定位"] mark').waitFor();assert.equal(await page.locator('[aria-label="原文定位"] mark').textContent(),valid.quote);report.checks.citationHighlight=true;
   }
  }else{
   Object.assign(report,{catalogId:previous.catalogId,documentId:previous.documentId,collectionId:previous.collectionId});
   step='restore-selection';await page.locator(`[data-job-id="${report.collectionId}"]`).click();await page.locator('#notice-picker').waitFor();
   await page.getByLabel('正文版本',{exact:true}).selectOption(report.documentId);
   const approvedId=process.argv[process.argv.indexOf('--candidate')+1];assert.ok(/^[0-9a-f-]{36}$/.test(approvedId));
   const article=page.locator(`[data-candidate-id="${approvedId}"]`);await article.waitFor();
   step='review';await article.getByRole('button',{name:'定位原文',exact:false}).click();await page.locator('[aria-label="原文定位"] mark').waitFor();
   const reviewed=(await(await context.request.get(root+'/documents/'+report.documentId+'/candidates')).json()).find(c=>c.id===approvedId);
   if(!reviewed.reviewReason||!['APPROVED','PUBLISHED'].includes(reviewed.status))throw new Error('CANDIDATE_REVIEW_REQUIRED');report.checks.reviewWithReason=true;assert.equal((await post('/candidates/'+approvedId+'/review',{decision:'REJECTED',reason:'不能覆盖已锁定审核'})).status(),409);report.checks.reviewImmutable=true;
   await article.getByRole('checkbox').check();
   step='publish';const publication=page.waitForResponse(r=>r.url()===root+'/publications'&&r.request().method()==='POST');
   await page.getByRole('button',{name:/发布选中关系/}).click();const result=await publication;assert.ok([200,202].includes(result.status()));
   const job=await wait('/evidence/jobs/'+(await result.json()).id);report.releaseId=job.resultId;
   await page.getByRole('button',{name:new RegExp('1 条陈述')}).first().click();
   const graph=await(await context.request.get(root+'/releases/'+report.releaseId)).json();assert.equal(graph.storage,'NEO4J');
   assert.ok(graph.edges.some(edge=>edge.type==='HAS_SOURCE_CLAIM'));assert.ok(graph.edges.some(edge=>edge.type==='SUPPORTED_BY'));
   const proof=graph.evidence.find(e=>e.scope==='PDF_TEXT_CITATION');assert.equal(proof.record.candidateId,approvedId);assert.equal(proof.snapshotHash,previous.pdfHash);
   report.checks.reviewedClaimAndDocumentReadFromNeo4j=true;
   await page.getByRole('button',{name:'审核来源陈述',exact:false}).first().click();
   await page.getByRole('heading',{name:'关系证据',exact:true}).waitFor();
   report.checks.publishedEvidenceVisible=true;
   step='reload';await page.reload({waitUntil:'domcontentloaded'});await page.locator('#notice-picker').waitFor();
   await page.getByRole('button',{name:new RegExp('1 条陈述')}).first().click();
   await page.getByRole('button',{name:'审核来源陈述',exact:false}).first().waitFor();report.checks.reloadRestoresPublication=true;
  }
  step='screenshots';await page.screenshot({path:output+(publishing?'/published-desktop.png':'/candidates-desktop.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await page.screenshot({path:output+(publishing?'/published-mobile.png':'/candidates-mobile.png'),fullPage:true});report.checks.mobileLayout=true;
  assert.deepEqual(errors,[]);report.checks.noBrowserErrors=true;report.status='passed';
 }catch(error){report.status='failed';report.failedStep=step;report.errorType=error.name;report.errorCode=/^[A-Z][A-Z_0-9]{0,80}$/.test(error.message)?error.message:undefined;if(error.name==='AssertionError')report.assertion={actual:error.actual,expected:error.expected};await page.screenshot({path:output+'/failure.png',fullPage:true}).catch(()=>{});process.exitCode=1;}
 finally{
  try{const csrf=await(await context.request.get(base+'/api/java/auth/csrf')).json();await context.request.post(base+'/api/java/auth/logout',{headers:{Origin:base,[csrf.headerName]:csrf.token}});await context.request.post(base+'/api/auth/sign-out',{headers:{Origin:base},data:{}});}catch{}
  await context.close();await browser.close();fs.writeFileSync(output+(publishing?'/publication-report.json':'/extraction-report.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }
}
main().catch(error=>{console.log(JSON.stringify({status:'failed',errorType:error.name}));process.exitCode=1});
