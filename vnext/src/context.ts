import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { parse } from 'yaml';
import { confined } from './tools.js';
import type { Json } from './types.js';
export async function instructions(cwd:string):Promise<string>{
 const seen=new Set<string>();const load=async(path:string,depth=0):Promise<string>=>{if(depth>12)throw new Error('指令 include 深度超限');const p=await confined(cwd,path);if(seen.has(p))throw new Error('指令 include 循环');seen.add(p);let text=await readFile(p,'utf8');for(const match of text.matchAll(/^@include\s+(.+)$/gm))text=text.replace(match[0],await load(match[1]!.trim(),depth+1));seen.delete(p);return text;};
 for(const path of ['CNCODE.md','AGENTS.md','CLAUDE.md'])try{return (await load(path)).slice(0,60000);}catch(e:any){if(e.code!=='ENOENT')throw e;}return '';
}
export interface Skill { name:string; description:string; body:string; allowedTools?:string[] }
export async function skills(cwd:string):Promise<Skill[]>{const result:Skill[]=[];for(const base of ['.cncode/skills','.agents/skills']){const dir=await confined(cwd,base);let names:string[]=[];try{names=await readdir(dir);}catch(e:any){if(e.code==='ENOENT')continue;throw e;}for(const name of names){try{const body=await readFile(await confined(cwd,join(base,name,'SKILL.md')),'utf8');const m=body.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);const meta:Json=m?parse(m[1]!)??{}:{};result.push({name:String(meta.name??name),description:String(meta.description??''),body:m?.[2]??body,allowedTools:meta['allowed-tools']});}catch(e:any){if(e.code!=='ENOENT'&&e.code!=='ENOTDIR')throw e;}}}return result;}
export const fingerprint=(s:unknown)=>createHash('sha256').update(JSON.stringify(s)).digest('hex');
