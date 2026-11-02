import { randomUUID } from 'node:crypto';
import type { AppConfig, EngineContext, Json, Message, ToolCall, Usage } from './types.js';
export function usage(raw:Json={}):Usage{return {input:raw.prompt_tokens??raw.input_tokens??null,output:raw.completion_tokens??raw.output_tokens??null,cacheRead:raw.prompt_cache_hit_tokens??raw.prompt_tokens_details?.cached_tokens??raw.cache_read_input_tokens??null,cacheWrite:raw.cache_creation_input_tokens??null,raw};}
export async function* sse(body:ReadableStream<Uint8Array>){
  const reader=body.getReader();const decoder=new TextDecoder();let buffer='';
  try {while(true){const x=await reader.read();buffer+=decoder.decode(x.value,{stream:!x.done});buffer=buffer.replace(/\r\n/g,'\n');let i;while((i=buffer.indexOf('\n\n'))>=0){const block=buffer.slice(0,i);buffer=buffer.slice(i+2);const data=block.split('\n').filter(x=>x.startsWith('data:')).map(x=>x.slice(5).trimStart()).join('\n');if(data&&data!=='[DONE]')yield JSON.parse(data);if(data==='[DONE]')return;}if(x.done){if(buffer.trim().startsWith('data:')){const data=buffer.slice(5).trim();if(data&&data!=='[DONE]')yield JSON.parse(data);}return;}}}finally{reader.releaseLock();}
}
export async function modelRequest(config:AppConfig,messages:Message[],tools:Json[],ctx:EngineContext,purpose='conversation'):Promise<Message>{
  const requestId=randomUUID(),start=performance.now();ctx.emit('model.start',{requestId,purpose});let received:Usage|undefined;
  try{
    const response=await fetch(config.baseUrl.replace(/\/$/,'')+(config.baseUrl.replace(/\/$/,'').endsWith('/chat/completions')?'':'/chat/completions'),{method:'POST',headers:{authorization:'Bearer '+config.apiKey,'content-type':'application/json'},body:JSON.stringify({model:config.model,messages,tools:tools.length?tools:undefined,max_tokens:8192,stream:true,stream_options:{include_usage:true}}),signal:AbortSignal.any([ctx.signal,AbortSignal.timeout(180000)])});
    if(!response.ok)throw new Error('模型 HTTP '+response.status+': '+(await response.text()).slice(0,1000));if(!response.body)throw new Error('模型响应没有流');
    let content='',reasoning='',finish='';const calls=new Map<number,ToolCall>();
    for await(const chunk of sse(response.body)){
      if(chunk.error)throw new Error(JSON.stringify(chunk.error));if(chunk.usage)received=usage(chunk.usage);
      const choice=chunk.choices?.[0];if(!choice)continue;const d=choice.delta??{};
      if(d.content){content+=d.content;ctx.emit('assistant.delta',{text:d.content,requestId,purpose});}if(d.reasoning_content)reasoning+=d.reasoning_content;
      for(const t of d.tool_calls??[]){const index=t.index??0;const c=calls.get(index)??{id:'',type:'function',function:{name:'',arguments:''}};if(t.id)c.id=t.id;if(t.function?.name)c.function.name+=t.function.name;if(t.function?.arguments)c.function.arguments+=t.function.arguments;calls.set(index,c);}
      if(choice.finish_reason)finish=choice.finish_reason;
    }
    if(!finish)throw new Error('模型流中断，缺少 finish_reason');if(finish==='length')throw new Error('模型输出达到长度限制');
    const toolCalls=[...calls.entries()].sort((a,b)=>a[0]-b[0]).map(([,v])=>{if(!v.id||!v.function.name)throw new Error('工具调用缺少 ID 或名称');return v;});
    return {role:'assistant',content:content||null,...(reasoning?{reasoning_content:reasoning}:{}),...(toolCalls.length?{tool_calls:toolCalls}:{})};
  } finally{ctx.emit('usage',{requestId,purpose,usage:received??usage()});ctx.emit('model.end',{requestId,purpose,durationMs:performance.now()-start});}
}
