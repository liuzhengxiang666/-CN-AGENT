import { DeepSeekHarness } from '@deepseek-ai/dsh-sdk-client';
import type { HarnessNotification } from '@deepseek-ai/dsh-sdk-client';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { stringify } from 'yaml';
import { usage } from '../provider.js';
import { requireModel } from '../config.js';
import type { AppConfig, EngineAdapter, EngineContext, Json } from '../types.js';
export class Dsh implements EngineAdapter {
 readonly capabilities={approval:true,questions:true,resume:true,stop:true,tools:true,usage:true,memory:true,toolTiming:'canonical-events'};
 private harness?:DeepSeekHarness;private current?:EngineContext;
 constructor(private config:AppConfig){}
 async close(){await this.harness?.close();this.harness=undefined;}
 async run(input:string,ctx:EngineContext){
  requireModel(this.config);this.current=ctx;const started=performance.now();
  if(!this.harness){
   const home=join(this.config.dataDir,'dsh',ctx.session.id);mkdirSync(home,{recursive:true});const patch=join(home,'cncode.patch.yml');
   writeFileSync(patch,stringify([{id:'llm-pi-ai',config:{providers:{'cncode-route':{apiKeyEnv:'CNCODE_DSH_KEY',api:'openai-completions',baseURL:this.config.baseUrl.replace(/\/chat\/completions\/?$/,''),compat:{thinkingFormat:'deepseek'},models:[{id:this.config.model,name:this.config.model,contextWindow:131072,maxTokens:8192}],retryPolicy:{mode:'normal',maxRetries:0}}}}},
    {id:'session-telemetry-otel',disabled:true},{id:'tool-subagent',disabled:true}]));
   this.harness=new DeepSeekHarness({dshHome:home,cwd:ctx.session.cwd,processCwd:ctx.session.cwd,profile:'sdk',patches:[patch],provider:'cncode-route',model:this.config.model,maxTokens:8192,initializeTimeoutMs:60000,approvalRequests:true,env:{...process.env,CNCODE_DSH_KEY:this.config.apiKey,DSH_TELEMETRY_MODE:'OFF',OPENVIKING_RECALL_PEER_SCOPE:'actor'},
    onUserQuestion:async(request,{signal})=>{const answer=await this.current!.interact('question',{native:request,question:request.questions.map(q=>JSON.stringify(q)).join('\n')},signal);return answer as any;}});
   this.harness.client.start();this.harness.client.onRequest(async(method,params)=>{if(method!=='approval/request')throw new Error('不支持 SDK 请求 '+method);const start=performance.now();const answer=await this.current!.interact('approval',params);this.current!.emit('human.end',{durationMs:performance.now()-start});return {outcome:answer.allow?'allowed-once':'rejected'};});
   await this.harness.start();if(ctx.session.dshSessionId)await this.harness.resumeSession(ctx.session.dshSessionId);
  }
  const session=this.harness.session(ctx.session.dshSessionId??ctx.session.id);ctx.session.dshSessionId=session.id;ctx.save();ctx.emit('startup.end',{durationMs:performance.now()-started});
  const stop=()=>{void this.close();};ctx.signal.addEventListener('abort',stop,{once:true});
  try{const result=await session.run(input,{onNotification:n=>this.observe(n,ctx)});ctx.signal.throwIfAborted();ctx.emit('assistant.message',{text:result.finalResponse});if(!result.finalResponse)throw new Error('DSH 已空闲，但没有交付回答；检查原始事件');}
  finally{ctx.signal.removeEventListener('abort',stop);this.current=undefined;}
 }
 observe(n:HarnessNotification,c:EngineContext){
  c.emit('dsh.notification',n);const e=n.params.event as Json|undefined;if(!e)return;const d=e.data??{};
  if(e.type==='assistant/message'){
   const u=d.usage??{};c.emit('usage',{requestId:`${e.sessionId??c.session.id}:${d.turn}:${d.step}`,purpose:'conversation',usage:{...usage(u),input:u.input??u.inputTokens??null,output:u.output??u.outputTokens??null,cacheRead:u.cacheRead??u.cacheReadTokens??null,cacheWrite:u.cacheWrite??u.cacheWriteTokens??null}});
  }
  if(e.type==='tool/call')c.emit('tool.request',{callId:d.callId,name:d.name,arguments:d.arguments});
  if(e.type==='tool/result')c.emit('tool.result',{callId:d.message?.toolCallId,result:d.message,meta:d.meta});
 }
}
