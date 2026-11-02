import { OpenVikingClient } from '@openviking/sdk';
import { fingerprint } from './context.js';
import type { AppConfig, EngineContext, Session } from './types.js';
export class Memory {
 constructor(private config:AppConfig){}
 peer(s:Session){return 'cncode-'+fingerprint(s.cwd.replaceAll('\\','/').toLowerCase()).slice(0,24);}
 client(s:Session){if(!this.config.memoryUrl)throw new Error('OpenViking 未配置；设置 OPENVIKING_URL');return new OpenVikingClient({baseUrl:this.config.memoryUrl,apiKey:this.config.memoryKey,actorPeerId:this.peer(s),timeout:15000,headers:{'X-OpenViking-Recall-Peer-Scope':'actor'}});}
 async recall(query:string,c:EngineContext){const start=performance.now();try{const result=await this.client(c.session).search(query,{targetUri:'viking://memories',limit:5});const text=JSON.stringify(result).slice(0,6000);c.emit('memory.recall',{peer:this.peer(c.session),query,result:text,budgetCharacters:6000,usage:null});return text;}finally{c.emit('memory.recall.end',{durationMs:performance.now()-start});}}
 async capture(input:string,output:string,c:EngineContext){const client=this.client(c.session),id='cncode-'+c.session.id;try{await client.getSession(id);}catch(e:any){if(e.status!==404&&e.statusCode!==404&&!String(e.message).includes('NOT_FOUND'))throw e;await client.createSession({sessionId:id});}await client.addMessage(id,{role:'user',content:input});await client.addMessage(id,{role:'assistant',content:output});c.emit('memory.captured',{sessionId:id,committed:false});}
 async commit(s:Session){const start=performance.now();const result=await this.client(s).commitSession('cncode-'+s.id);return {state:'submitted',result,durationMs:performance.now()-start,usage:null};}
 async task(s:Session,id:string){return this.client(s).getTask(id);}
 async list(s:Session){return this.client(s).list('viking://memories',{recursive:true});}
 async read(s:Session,uri:string){this.check(uri);return this.client(s).read(uri);}
 async remove(s:Session,uri:string){this.check(uri);return this.client(s).remove(uri,{recursive:false});}
 private check(uri:string){if(!uri.startsWith('viking://memories/')&&!uri.includes('/memories/'))throw new Error('仅允许管理记忆 URI');}
}
