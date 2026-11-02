import { mkdir, cp, readFile, writeFile, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
const exec=promisify(execFile);
export const tasks=[
 {id:'phone-number',repo:'https://github.com/Aider-AI/polyglot-benchmark.git',commit:'7e0611e77b54e2dea774cdc0aa00cf9f7ed6144f',path:'javascript/exercises/practice/phone-number',seconds:300},
 {id:'kv-store-grpc',repo:'https://github.com/harbor-framework/terminal-bench-2.git',commit:'2fd12b88aafdd04a52c298e3940bcb189f9766d6',path:'kv-store-grpc',seconds:900}
] as const;
export async function hashTree(root:string):Promise<Record<string,string>>{
 const out:Record<string,string>={};async function walk(dir:string,base=''){for(const item of await readdir(dir,{withFileTypes:true})){if(item.name==='node_modules'||item.name==='.git')continue;const rel=base+item.name,p=join(dir,item.name);if(item.isDirectory())await walk(p,rel+'/');else out[rel]=createHash('sha256').update(await readFile(p)).digest('hex');}}await walk(root);return out;
}
export async function prepare(root:string){
 await mkdir(root,{recursive:true});
 for(const task of tasks){
  const clone=join(root,task.id+'-source');try{await exec('git',['clone','--filter=blob:none','--no-checkout',task.repo,clone]);}catch(e){await exec('git',['-C',clone,'rev-parse','--git-dir']);}
  await exec('git',['-C',clone,'sparse-checkout','set',task.path]);await exec('git',['-C',clone,'checkout','--detach',task.commit]);
  const {stdout}=await exec('git',['-C',clone,'rev-parse','HEAD']);if(stdout.trim()!==task.commit)throw new Error('Fixture commit mismatch');
  const target=join(root,task.id);await mkdir(target,{recursive:true});await cp(join(clone,task.path),target,{recursive:true});
  const hashes=await hashTree(target);await writeFile(join(root,task.id+'.manifest.json'),JSON.stringify({...task,hashes},null,2));
 }
}
