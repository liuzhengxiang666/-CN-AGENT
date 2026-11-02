// DSH 0.2.2-alpha.2 documented dispatch and LLM waterfalls.
// Runs inside the child. Transport arrival timestamps are never execution time.
import { appendFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
export const name='cncode-observer';
export const inject=['tools','llm'];
export function apply(ctx){
 const emit=(type,data)=>appendFileSync(process.env.CNCODE_DSH_OBSERVATIONS,JSON.stringify({type,data,at:new Date().toISOString()})+'\n');
 emit('observer.ready',{version:'0.2.2-alpha.2'});
 ctx.on('tools/execute',async(exec,next)=>{
   const data={callId:exec.callId,name:exec.name,source:'dsh-tools/execute'},start=performance.now();emit('tool.start',data);
   let success=false;
   try{const result=await next();success=result.isError===false;return result;}
   finally{emit('tool.end',{...data,executed:true,success,durationMs:performance.now()-start});}
 });
 ctx.on('llm/stream',async function*(options,next){
   const requestId=randomUUID(),purpose=options.purpose??'conversation',start=performance.now();let usage,failure;
   emit('model.start',{requestId,purpose});
   try{for await(const chunk of next()){if(chunk.type==='usage')usage=chunk.usage;if(chunk.type==='finish'&&chunk.reason?.kind==='error')failure=chunk.reason.failure;yield chunk;}}
   finally{emit('dsh.usage',{requestId,purpose,raw:failure?null:usage??null,adapterUsage:usage??null,failure});emit('model.end',{requestId,purpose,durationMs:performance.now()-start});}
 });
}
