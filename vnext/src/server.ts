import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, resolve, extname, relative } from 'node:path';
import { timingSafeEqual } from 'node:crypto';
import type { Service } from './service.js';
import type { Json, RunEvent } from './types.js';
const json=(res:ServerResponse,value:unknown,status=200)=>{res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});res.end(JSON.stringify(value));};
async function body(req:IncomingMessage):Promise<Json>{let s='';for await(const chunk of req){s+=chunk;if(s.length>1000000)throw new Error('请求过大');}return s?JSON.parse(s):{};}
export function server(service:Service,webDir=resolve('web-dist')){
 const http=createServer(async(req,res)=>{try{
  const url=new URL(req.url??'/','http://localhost'),parts=url.pathname.split('/').filter(Boolean);res.setHeader('x-content-type-options','nosniff');res.setHeader('content-security-policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'");
  if(url.pathname==='/health'){json(res,{ok:true});return;}
  if(parts[0]==='api'){
   const token=String(req.headers.authorization??'').replace(/^Bearer /,'');const expected=service.config.token;if(!expected||Buffer.byteLength(token)!==Buffer.byteLength(expected)||!timingSafeEqual(Buffer.from(token),Buffer.from(expected))){json(res,{error:'需要访问令牌'},401);return;}
   const origin=req.headers.origin;if(origin&&origin!==`http://${req.headers.host}`&&origin!==`https://${req.headers.host}`)throw new Error('拒绝跨来源请求');
   if(parts[1]!=='v1')throw new Error('未知 API 版本');const resource=parts[2],id=parts[3],action=parts[4];
   if(resource==='config'){json(res,{model:service.config.model,workspace:process.env.CNCODE_WORKSPACE??process.cwd(),memoryConfigured:!!service.config.memoryUrl,engines:['cncode','dsh']});return;}
   if(resource==='sessions'){
    if(req.method==='GET'&&!id){json(res,[...service.sessions.values()].map(({messages,inputs,...s})=>s));return;}
    if(req.method==='POST'&&!id){const b=await body(req);json(res,await service.create(b.engine,b.cwd,b.memory===true),201);return;}
    if(!id)throw new Error('缺少会话 ID');const session=service.get(id);
    if(action==='inputs'&&req.method==='POST'){const b=await body(req);json(res,{runId:service.submit(id,b.inputId,b.text)},202);return;}
    if(action==='stop'&&req.method==='POST'){await service.stop(id);json(res,{stopped:true});return;}
    if(action==='memory'){if(!session.memory)throw new Error('该会话未启用记忆');
     const b=req.method==='GET'?{}:await body(req);const m=service.memory;
     if(req.method==='GET')json(res,url.searchParams.has('task')?await m.task(session,url.searchParams.get('task')!):url.searchParams.has('uri')?await m.read(session,url.searchParams.get('uri')!):await m.list(session));
     else if(req.method==='DELETE')json(res,await m.remove(session,b.uri));
     else json(res,await m.commit(session));return;
    }
    if(!action){json(res,session);return;}
   }
   if(resource==='runs'){
    if(!id){json(res,service.records.runs().reverse());return;}
    if(action==='events'){
     service.records.get(id);const after=Number(req.headers['last-event-id']??url.searchParams.get('after')??0);if(!Number.isSafeInteger(after)||after<0)throw new Error('无效事件序号');res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache','connection':'keep-alive'});
     const send=(e:RunEvent)=>res.write(`id: ${e.seq}\ndata: ${JSON.stringify(e)}\n\n`);
     const listen=(e:RunEvent)=>send(e);service.records.events.on(id,listen);for(const e of service.records.read(id,after))send(e);
     const keep=setInterval(()=>res.write(': heartbeat\n\n'),15000);res.on('close',()=>{clearInterval(keep);service.records.events.off(id,listen);});return;
    }
    json(res,{run:service.records.get(id),events:service.records.read(id)});return;
   }
   if(resource==='interactions'){
    if(req.method==='GET'){json(res,[...service.pending.values()]);return;}
    if(req.method==='POST'&&id){service.answer(id,await body(req));json(res,{accepted:true});return;}
   }
   if(resource==='evals'){json(res,service.records.runs().filter(r=>r.versions.task));return;}
   json(res,{error:'接口不存在'},404);return;
  }
  if(req.method!=='GET'){json(res,{error:'方法不支持'},405);return;}
  const file=resolve(webDir,'.'+decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname));if(relative(webDir,file).startsWith('..'))throw new Error('路径越界');
  try{const data=await readFile(file);res.setHeader('content-type',({'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'} as Json)[extname(file)]??'application/octet-stream');res.end(data);}catch{json(res,{error:'界面尚未构建；运行 pnpm build'},404);}
 }catch(e:any){if(!res.headersSent)json(res,{error:e.message},400);else res.end();}});
 return http;
}
