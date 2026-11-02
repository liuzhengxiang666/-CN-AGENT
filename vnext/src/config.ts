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
    memoryUrl:process.env.OPENVIKING_URL,memoryKey:process.env.OPENVIKING_API_KEY,mcp:v.mcp_servers??[] };
}
export function requireModel(c:AppConfig){if(!c.model||!c.baseUrl||!c.apiKey)throw new Error('缺少模型配置；设置 CNCODE_CONFIG 或 CNCODE_MODEL / CNCODE_BASE_URL / CNCODE_API_KEY');if(c.protocol!=='openai')throw new Error('TS 主线目前需要 OpenAI-compatible 协议；Java Anthropic 路径保留');}
