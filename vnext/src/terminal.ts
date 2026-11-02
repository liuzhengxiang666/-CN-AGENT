import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import { randomUUID } from 'node:crypto';
import type { Json } from './types.js';
const base=process.env.CNCODE_SERVER??'http://127.0.0.1:3040',token=process.env.CNCODE_TOKEN??'';
const api=async(path:string,body?:Json)=>{const r=await fetch(base+'/api/v1/'+path,{method:body?'POST':'GET',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:body?JSON.stringify(body):undefined});const b=await r.json() as Json;if(!r.ok)throw new Error(b.error);return b;};
const rl=createInterface({input:stdin,output:stdout});let sessionId=process.env.CNCODE_SESSION??'';let busy=false;
rl.on('SIGINT',()=>{if(sessionId)void api(`sessions/${sessionId}/stop`,{}).catch(console.error);});
try{if(!sessionId){const c=await api('config');sessionId=(await api('sessions',{engine:process.argv[2]??'cncode',cwd:process.env.CNCODE_WORKSPACE??c.workspace})).id;}
console.log('CN Code 会话 '+sessionId+'；/quit 退出，/stop 停止');
while(true){const text=await rl.question('你 › ');if(text==='/quit')break;if(text==='/stop'){await api(`sessions/${sessionId}/stop`,{});continue;}if(!text.trim())continue;const {runId}=await api(`sessions/${sessionId}/inputs`,{inputId:randomUUID(),text});let finished=false,seq=0;while(!finished){const result=await api('runs/'+runId);for(const e of result.events.filter((e:Json)=>e.seq>seq)){seq=e.seq;if(e.type==='assistant.delta')stdout.write(e.data.text);if(e.type==='assistant.message'&&!result.events.some((x:Json)=>x.type==='assistant.delta'))console.log(e.data.text);if(e.type==='interaction'){const i=e.data;const answer=await rl.question(`\n${i.kind==='approval'?'批准工具？ [y/N]':JSON.stringify(i.payload)} › `);let response:Json=i.kind==='approval'?{allow:answer.toLowerCase()==='y'}:{text:answer};if(i.payload.native)response={answers:i.payload.native.questions.map((q:Json)=>({id:q.id,selected:[],custom:answer}))};await api('interactions/'+i.id,response);}if(e.type==='run.end'){finished=true;console.log('\n['+e.data.state+']');}}if(!finished)await new Promise(r=>setTimeout(r,250));}}
}finally{rl.close();}
