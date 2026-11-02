const {app,BrowserWindow}=require('electron');
const {fork}=require('node:child_process');
const path=require('node:path');
const os=require('node:os');
app.setPath('userData',process.env.CNCODE_DESKTOP_DATA||path.resolve(__dirname,'../../.cncode/vnext/desktop'));
let child;let window;let quitting=false;
app.whenReady().then(()=>{
 child=fork(path.resolve(__dirname,'../dist/server-main.js'),[],{cwd:path.resolve(__dirname,'..'),execPath:process.execPath,env:{...process.env,ELECTRON_RUN_AS_NODE:'1',CNCODE_PORT:'0'},stdio:['ignore','pipe','pipe','ipc'],windowsHide:true});
 child.on('message',async m=>{if(m.type!=='ready')return;window=new BrowserWindow({width:1440,height:920,minWidth:1000,minHeight:650,backgroundColor:'#14171b',title:'CN Code',webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}});window.webContents.setWindowOpenHandler(()=>({action:'deny'}));window.webContents.on('will-navigate',(e,url)=>{if(!url.startsWith(m.url+'/'))e.preventDefault();});await window.loadURL(m.url+'/#token='+m.token);});
 child.stderr.on('data',b=>process.stderr.write(b));child.on('exit',code=>{if(!quitting&&code)app.quit();});
});
app.on('window-all-closed',()=>app.quit());
app.on('before-quit',e=>{if(quitting||!child||child.exitCode!==null)return;e.preventDefault();quitting=true;child.send({type:'shutdown'});const deadline=setTimeout(()=>{child.kill();app.quit();},10000);child.once('exit',()=>{clearTimeout(deadline);app.quit();});});
