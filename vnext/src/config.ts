import { readFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { parse } from 'yaml';
import type { AppConfig } from './types.js';
export function loadConfig():AppConfig {
  const configPath=process.env.CNCODE_CONFIG??resolve(homedir(),'.cncode/config.yaml');
  const v=existsSync(configPath)?parse(readFileSync(configPath,'utf8'))??{}:{};
  return { model:process.env.CNCODE_MODEL??v.model??'',baseUrl:process.env.CNCODE_BASE_URL??v.base_url??'',apiKey:process.env.CNCODE_API_KEY??v.api_key??'',protocol:process.env.CNCODE_PROTOCOL??v.protocol??'openai',
    dataDir:resolve(process.env.CNCODE_DATA_DIR??'../.cncode/vnext'),token:process.env.CNCODE_TOKEN??'',host:process.env.CNCODE_HOST??'127.0.0.1',port:Number(process.env.CNCODE_PORT??3040),configPath,
    memoryUrl:process.env.OPENVIKING_URL,memoryKey:process.env.OPENVIKING_API_KEY,mcp:migrateMcp(v) };
}
export function requireModel(c:AppConfig){if(!c.model||!c.baseUrl||!c.apiKey)throw new Error('缺少模型配置；设置 CNCODE_CONFIG 或 CNCODE_MODEL / CNCODE_BASE_URL / CNCODE_API_KEY');if(c.protocol!=='openai')throw new Error('TS 主线目前需要 OpenAI-compatible 协议；Java Anthropic 路径保留');}

export function migrateMcp(v:Record<string,any>):Record<string,any>[] {
 if(Array.isArray(v.mcp_servers))return v.mcp_servers;
 if(v.mcp_servers&&typeof v.mcp_servers==='object')return Object.entries(v.mcp_servers).map(([name,c])=>({name,...c as object}));
 const groups=new Map<string,Record<string,any>>();
 for(const [key,value] of Object.entries(v)){
   const m=key.match(/^mcp\.server\.([^.]+)\.(.+)$/);if(!m)continue;
   const [,name,field]=m as [string,string,string];const c=groups.get(name)??{name};groups.set(name,c);
   if(field.startsWith('env.')||field.startsWith('headers.')){const dot=field.indexOf('.'),parent=field.slice(0,dot);(c[parent]??={})[field.slice(dot+1)]=value;}
   else c[field]=field==='args'?String(value).split(',').map(x=>x.trim()).filter(Boolean):value;
 }
 return [...groups.values()];
}
