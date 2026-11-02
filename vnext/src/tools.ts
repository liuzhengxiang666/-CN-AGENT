import { realpath, stat, readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { resolve, relative, dirname, isAbsolute, join } from 'node:path';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import type { EngineContext, Json, ToolCall } from './types.js';
export async function confined(root:string,path:string){const base=await realpath(root),target=resolve(base,path);const inside=(p:string)=>{const r=relative(base,p);if(r==='..'||r.startsWith('..'+(process.platform==='win32'?'\\':'/'))||isAbsolute(r))throw new Error('路径越过工作区');};inside(target);let parent=target;while(true){try{inside(await realpath(parent));break;}catch(e:any){if(e.code!=='ENOENT')throw e;const up=dirname(parent);if(up===parent)throw e;parent=up;}}return target;}
export function command(command:string,args:string[],cwd:string,signal?:AbortSignal,timeout=120000):Promise<{code:number;output:string}>{
  return new Promise((resolvePromise,reject)=>{const p=spawn(command,args,{cwd,windowsHide:true,signal,timeout,env:{...process.env}});let output='';const add=(v:Buffer)=>{if(output.length<64000)output+=v.toString().slice(0,64000-output.length);};p.stdout.on('data',add);p.stderr.on('data',add);p.once('error',reject);p.once('close',code=>resolvePromise({code:code??-1,output}));});
}
export interface Tool { schema:Json; readonly:boolean; run:(a:Json,c:EngineContext)=>Promise<unknown> }
const schema=(name:string,description:string,properties:Json,required:string[]=[])=>({type:'function',function:{name,description,parameters:{type:'object',properties,required,additionalProperties:false}}});
const str={type:'string'};
export function builtins():Map<string,Tool>{return new Map<string,Tool>([
 ['read_file',{readonly:true,schema:schema('read_file','读取工作区内文件',{path:str},['path']),run:async(a,c)=>(await readFile(await confined(c.session.cwd,a.path),'utf8')).slice(0,40000)}],
 ['list_files',{readonly:true,schema:schema('list_files','列出工作区目录',{path:str}),run:async(a,c)=>readdir(await confined(c.session.cwd,a.path??'.'))}],
 ['search',{readonly:true,schema:schema('search','在工作区用 rg 检索文本，pattern 是正则表达式',{pattern:str,path:str},['pattern']),run:async(a,c)=>command('rg',['-n','--max-count','30','--',a.pattern,await confined(c.session.cwd,a.path??'.')],c.session.cwd,c.signal)}],
 ['write_file',{readonly:false,schema:schema('write_file','创建或覆盖工作区文件',{path:str,content:str},['path','content']),run:async(a,c)=>{const p=await confined(c.session.cwd,a.path);await mkdir(dirname(p),{recursive:true});await writeFile(p,a.content);return 'written';}}],
 ['edit_file',{readonly:false,schema:schema('edit_file','唯一匹配文本替换，避免意外修改其他位置',{path:str,old_text:str,new_text:str},['path','old_text','new_text']),run:async(a,c)=>{const p=await confined(c.session.cwd,a.path),text=await readFile(p,'utf8');if(!a.old_text||text.split(a.old_text).length!==2)throw new Error('old_text 必须唯一匹配');await writeFile(p,text.replace(a.old_text,a.new_text));return 'edited';}}],
 ['shell',{readonly:false,schema:schema('shell','在工作区执行命令；任意 shell 命令都需要用户批准',{command:str},['command']),run:async(a,c)=>command(process.platform==='win32'?'powershell.exe':'/bin/sh',process.platform==='win32'?['-NoProfile','-NonInteractive','-Command',a.command]:['-lc',a.command],c.session.cwd,c.signal)}],
 ['ask_user_question',{readonly:true,schema:schema('ask_user_question','向用户澄清具体问题',{question:str,options:{type:'array',items:str}},['question']),run:async(a,c)=>c.interact('question',{question:a.question,options:a.options??[]})}]
]);}
export async function executeTool(call:ToolCall,tools:Map<string,Tool>,ctx:EngineContext){
 const tool=tools.get(call.function.name);let start:number|undefined;
 try{if(!tool)throw new Error('未知工具 '+call.function.name);const args=JSON.parse(call.function.arguments||'{}');
   if(!tool.readonly&&process.env.CNCODE_ALLOW_TOOLS!=='1'){
    const wait=performance.now();const answer=await ctx.interact('approval',{callId:call.id,tool:call.function.name,args});ctx.emit('human.end',{callId:call.id,durationMs:performance.now()-wait});if(answer.allow!==true)throw new Error('用户拒绝工具执行');
   }
   ctx.signal.throwIfAborted();start=performance.now();ctx.emit('tool.start',{callId:call.id,name:call.function.name,args});const result=await tool.run(args,ctx);return JSON.stringify(result);
 }catch(e:any){return JSON.stringify({error:e.message});}
 finally{ctx.emit('tool.end',{callId:call.id,name:call.function.name,executed:start!==undefined,durationMs:start===undefined?0:performance.now()-start});}
}
