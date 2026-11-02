import { DeepSeekHarness } from '@deepseek-ai/dsh-sdk-client';
import type { HarnessNotification } from '@deepseek-ai/dsh-sdk-client';
import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { stringify } from 'yaml';
import type { Usage } from '../types.js';
import { requireModel } from '../config.js';
import type { AppConfig, EngineAdapter, EngineContext, Json } from '../types.js';
export class Dsh implements EngineAdapter {
 readonly capabilities={approval:true,questions:true,resume:true,stop:true,tools:true,usage:true,memory:false,toolTiming:'dsh-tools/execute'};
 private harness?:DeepSeekHarness;private current?:EngineContext;private closing?:Promise<void>;
 constructor(private config:AppConfig){}
 async close(){if(this.closing)return this.closing;const h=this.harness;this.harness=undefined;if(h)this.closing=h.close();await this.closing;}
 async run(input:string,ctx:EngineContext){
  requireModel(this.config);if(ctx.session.memory)throw new Error('DSH OpenViking 插件尚未验收，不能宣称已启用记忆');ctx.signal.throwIfAborted();this.current=ctx;const started=performance.now();
  const home=join(this.config.dataDir,'dsh',ctx.session.id);mkdirSync(home,{recursive:true});const observations=join(home,'observations.jsonl');writeFileSync(observations,'');let consumed=0;
  const flush=()=>{if(!existsSync(observations))return;const lines=readFileSync(observations,'utf8').split('\n').filter(Boolean);while(consumed<lines.length){const event=JSON.parse(lines[consumed++]!);ctx.emit(event.type==='dsh.usage'?'usage':event.type,event.type==='dsh.usage'?{requestId:event.data.requestId,purpose:event.data.purpose,usage:dshUsage(event.data.raw??{})}:{...event.data,executedAt:event.at});}};
  const poll=setInterval(()=>{try{flush();}catch(e){ctx.emit('observer.error',{message:String(e)});}},100);
  try{if(!this.harness){
   const patch=join(home,'cncode.patch.yml');
   writeFileSync(patch,stringify([{id:'llm-pi-ai',config:{providers:{'cncode-route':{apiKeyEnv:'CNCODE_DSH_KEY',api:'openai-completions',baseURL:this.config.baseUrl.replace(/\/chat\/completions\/?$/,''),compat:{thinkingFormat:'deepseek'},models:[{id:this.config.model,name:this.config.model,contextWindow:131072,maxTokens:8192}],retryPolicy:{mode:'normal',maxRetries:0}}}}},
    {insert:[{id:'cncode-observer',name:fileURLToPath(new URL('../../runtime/dsh-observer.mjs',import.meta.url)).replaceAll('\\','/')}]},{id:'session-telemetry-otel',disabled:true},{id:'tool-subagent',disabled:true}]));
   this.harness=new DeepSeekHarness({dshHome:home,cwd:ctx.session.cwd,processCwd:ctx.session.cwd,profile:'sdk',patches:[patch],provider:'cncode-route',model:this.config.model,maxTokens:8192,initializeTimeoutMs:60000,approvalRequests:true,env:{...process.env,CNCODE_DSH_OBSERVATIONS:observations,CNCODE_DSH_KEY:this.config.apiKey,DSH_TELEMETRY_MODE:'OFF',OPENVIKING_RECALL_PEER_SCOPE:'actor'},
    onUserQuestion:async(request,{signal})=>{const answer=await this.current!.interact('question',{native:request,question:request.questions.map(q=>JSON.stringify(q)).join('\n')},signal);return answer as any;}});
   this.harness.client.start();this.harness.client.onRequest(async(method,params)=>{if(method!=='approval/request')throw new Error('不支持 SDK 请求 '+method);const answer=await this.current!.interact('approval',params);return {outcome:answer.allow?'allowed-once':'rejected'};});
   await this.harness.start();flush();if(!existsSync(observations)||!readFileSync(observations,'utf8').includes('observer.ready'))throw new Error('DSH 执行观测插件未加载，拒绝无计时运行');if(ctx.session.dshSessionId)await this.harness.resumeSession(ctx.session.dshSessionId);
  }
  const session=this.harness.session(ctx.session.dshSessionId??ctx.session.id);ctx.session.dshSessionId=session.id;ctx.save();ctx.emit('startup.end',{durationMs:performance.now()-started});
  ctx.signal.throwIfAborted();const stop=()=>{void this.close().catch(e=>ctx.emit('cleanup.error',{message:String(e)}));};ctx.signal.addEventListener('abort',stop,{once:true});
  try{const result=await session.run(input,{onNotification:n=>this.observe(n,ctx)});ctx.signal.throwIfAborted();const failure=result.events.findLast(e=>e.type==='turn/end'&&(e.data as Json).reason?.kind==='error');if(failure)throw new Error((failure.data as Json).reason.error?.message??'DSH turn error');ctx.emit('assistant.message',{text:result.finalResponse});if(!result.finalResponse)throw new Error('DSH 已空闲，但没有交付回答；检查原始事件');}
  finally{ctx.signal.removeEventListener('abort',stop);}
  }finally{clearInterval(poll);flush();this.current=undefined;}
 }
 observe(n:HarnessNotification,c:EngineContext){
  c.emit('dsh.notification',n);const e=n.params.event as Json|undefined;if(!e)return;const d=e.data??{};
  if(e.type==='tool/call')c.emit('tool.request',{callId:d.callId,name:d.name,arguments:d.arguments});
  if(e.type==='tool/result')c.emit('tool.result',{callId:d.message?.toolCallId,result:d.message,meta:d.meta});
 }
}

/** DSH counts uncached input separately. Prefer its authoritative full-call total. */
export function dshUsage(raw:Json):Usage {
 const count=(v:unknown)=>typeof v==='number'&&Number.isFinite(v)&&v>=0?v:null;
 const output=count(raw.outputTokens),uncached=count(raw.inputTokens),read=count(raw.cacheReadTokens),write=count(raw.cacheWriteTokens),total=count(raw.totalTokens);
 const input=total!==null&&output!==null&&total>=output?total-output:uncached!==null&&read!==null&&write!==null?uncached+read+write:null;
 return {input,output,cacheRead:read,cacheWrite:write,raw:{...raw,inputSemantics:'dsh-disjoint',uncachedInput:uncached}};
}
