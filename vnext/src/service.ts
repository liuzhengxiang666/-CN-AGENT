import { randomUUID } from 'node:crypto';
import { stat, mkdir, copyFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { resolve, join, dirname, relative, isAbsolute } from 'node:path';
import { confined } from './tools.js';
import { Records } from './records.js';
import { CnCode } from './engines/cncode.js';
import { Dsh } from './engines/dsh.js';
import { Memory } from './memory.js';
import type { AppConfig, Session, EngineId, EngineAdapter, EngineContext, Interaction, Json } from './types.js';
export class Service {
 readonly records:Records;readonly sessions=new Map<string,Session>();readonly memory:Memory;
 readonly pending=new Map<string,Interaction>();private waiters=new Map<string,(v:Json)=>void>();private active=new Map<string,{engine:EngineAdapter;controller:AbortController;done:Promise<void>}>();
 private revision:string;
 constructor(readonly config:AppConfig,private engineFactory?:(id:EngineId)=>EngineAdapter){this.records=new Records(config.dataDir,[config.apiKey,config.memoryKey??'',config.token,...config.mcp.flatMap(c=>[...Object.values(c.env??{}),...Object.values(c.headers??{})]).filter((v):v is string=>typeof v==='string'&&v.length>3)]);this.memory=new Memory(config);for(const s of this.records.sessions()){delete s.activeRun;this.sessions.set(s.id,s);}for(const r of this.records.runs())if(r.state==='running')this.records.finish(r,'interrupted','服务重启，中断运行未自动重试');try{this.revision=execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8',cwd:new URL('../',import.meta.url)}).trim();}catch{this.revision='unknown';}}
 async create(engine:EngineId,cwd:string,memory=false){if(!['cncode','dsh'].includes(engine))throw new Error('不支持的内核');if(typeof cwd!=='string'||!cwd.trim())throw new Error('需要工作区目录');if(memory&&!this.config.memoryUrl)throw new Error('OpenViking 未配置');if(memory&&engine==='dsh')throw new Error('DSH 记忆插件尚未接入验收');cwd=resolve(cwd);if(!(await stat(cwd)).isDirectory())throw new Error('工作区不是目录');const s:Session={id:randomUUID(),engine,cwd,memory,createdAt:new Date().toISOString(),messages:[],inputs:{}};this.sessions.set(s.id,s);this.records.session(s);return s;}
 get(id:string){const s=this.sessions.get(id);if(!s)throw new Error('会话不存在');return s;}
 submit(id:string,inputId:string,input:string){const s=this.get(id);if(typeof inputId!=='string'||!inputId||typeof input!=='string'||!input.trim())throw new Error('需要 inputId 和非空文本');if(Object.hasOwn(s.inputs,inputId))return s.inputs[inputId]!;if(s.activeRun)throw new Error('当前会话有运行中的任务');
  const run=this.records.begin(s,this.config.model,{code:this.revision,engine:s.engine==='dsh'?'0.2.2-alpha.2':'0.2.0',memory:s.memory?'openviking':'off',skillScope:'project-only',prompt:'cncode-vnext-v1'});s.inputs[inputId]=run.id;s.activeRun=run.id;this.records.session(s);
  const engine:EngineAdapter=this.engineFactory?.(s.engine)??(s.engine==='cncode'?new CnCode(this.config,this.memory):new Dsh(this.config));const controller=new AbortController();
  const ctx:EngineContext={session:s,signal:controller.signal,emit:(t,d)=>this.records.emit(run.id,t,d),save:()=>this.records.session(s),interact:(kind,payload,signal)=>this.interact({id:randomUUID(),kind,payload,runId:run.id},signal?AbortSignal.any([signal,controller.signal]):controller.signal)};
  ctx.emit('run.start',{input,engine:s.engine,sessionId:s.id});
  const done=Promise.resolve().then(async()=>{
    let state:'completed'|'cancelled'|'failed'='completed',error:string|undefined;
    try{await engine.run(input,ctx);controller.signal.throwIfAborted();}catch(e:any){state=controller.signal.aborted?'cancelled':'failed';error=e.message;}
    try{await engine.close();}catch(e){ctx.emit('cleanup.error',{message:String(e)});state=controller.signal.aborted?'cancelled':'failed';error??=String(e);}
    await this.artifacts(run.id,s).catch(e=>ctx.emit('artifact.error',{message:String(e)}));
    delete s.activeRun;this.active.delete(s.id);this.records.session(s);
    this.records.finish(run,state,error);
  });
  this.active.set(s.id,{engine,controller,done});return run.id;
 }
 private async artifacts(runId:string,s:Session){
  const paths=new Set<string>();for(const event of this.records.read(runId)){
   if(event.type==='tool.start'&&['write_file','edit_file'].includes(event.data.name))paths.add(event.data.args.path);
   if(event.type==='tool.request'&&['write','edit'].includes(event.data.name)&&event.data.arguments?.file_path)paths.add(event.data.arguments.file_path);
  }
  for(const path of paths){
   const source=await confined(s.cwd,path),rel=relative(s.cwd,source);if(!rel||isAbsolute(rel)||rel.startsWith('..'))continue;
   const dest=join(this.records.runDir(runId),'artifacts',rel);await mkdir(dirname(dest),{recursive:true});
   const {readFile}=await import('node:fs/promises');const bytes=await readFile(source);if(bytes.length>1024*1024){this.records.emit(runId,'artifact.skipped',{path:rel,reason:'size > 1 MiB'});continue;}
   // Text snapshots use the same credential redaction as events.
   await writeFile(dest,this.records.scrub(bytes.toString('utf8')));this.records.emit(runId,'artifact.saved',{path:rel,bytes:bytes.length,redacted:true});
  }
 }
 private interact(i:Interaction,signal:AbortSignal){signal.throwIfAborted();this.pending.set(i.id,i);this.records.emit(i.runId,'interaction',i);return new Promise<Json>((resolvePromise,reject)=>{const abort=()=>{this.pending.delete(i.id);this.waiters.delete(i.id);this.records.emit(i.runId,'interaction.cancelled',{id:i.id});reject(new Error('交互已取消'));};signal.addEventListener('abort',abort,{once:true});this.waiters.set(i.id,v=>{signal.removeEventListener('abort',abort);this.pending.delete(i.id);this.waiters.delete(i.id);this.records.emit(i.runId,'interaction.answered',{id:i.id,answer:v});resolvePromise(v);});});}
 answer(id:string,answer:Json){const waiter=this.waiters.get(id);if(!waiter)throw new Error('交互不存在或已回答');waiter(answer);}
 async stop(id:string){this.get(id);const run=this.active.get(id);run?.controller.abort();await run?.done;}
 async wait(id:string){await this.active.get(id)?.done;}
 async close(){await Promise.allSettled([...this.active.keys()].map(id=>this.stop(id)));}
}
