export type EngineId = 'cncode' | 'dsh';
export type Json = Record<string, any>;
export interface Message { role: 'system'|'user'|'assistant'|'tool'; content: string|null; tool_calls?: ToolCall[]; tool_call_id?: string; reasoning_content?: string }
export interface ToolCall { id:string; type:'function'; function:{name:string; arguments:string} }
export interface Usage { input:number|null; output:number|null; cacheRead:number|null; cacheWrite:number|null; raw:Json }
export interface RunEvent { seq:number; at:string; runId:string; type:string; data:Json }
export interface Session { id:string; engine:EngineId; cwd:string; createdAt:string; messages:Message[]; inputs:Record<string,string>; memory:boolean; dshSessionId?:string; activeRun?:string; allowedTools?:string[] }
export interface Run { id:string; sessionId:string; engine:EngineId; model:string; startedAt:string; endedAt?:string; state:'running'|'completed'|'failed'|'cancelled'|'interrupted'; outcome:'unverified'|'pass'|'fail'|'infra_error'; versions:Json; metrics:Json; error?:string }
export interface Interaction { id:string; kind:'approval'|'question'; payload:Json; runId:string }
export interface EngineContext { session:Session; signal:AbortSignal; emit:(type:string,data?:Json)=>void; interact:(kind:Interaction['kind'],payload:Json,signal?:AbortSignal)=>Promise<Json>; save:()=>void }
export interface EngineAdapter { readonly capabilities:Json; run(input:string,ctx:EngineContext):Promise<void>; close():Promise<void> }
export interface AppConfig { model:string; baseUrl:string; apiKey:string; protocol:string; dataDir:string; token:string; host:string; port:number; configPath:string; memoryUrl?:string; memoryKey?:string; mcp:Json[] }
