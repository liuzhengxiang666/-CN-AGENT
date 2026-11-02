# Trusted verifier: isolated from the Agent filesystem and Python environment.
import pathlib,sys,socket,grpc,subprocess,tempfile
temp=tempfile.mkdtemp();subprocess.run([sys.executable,'-m','grpc_tools.protoc','-I/app','--python_out='+temp,'--grpc_python_out='+temp,'/app/kv-store.proto'],check=True);sys.path.insert(0,temp)
for name in ['kv-store.proto','kv_store_pb2.py','kv_store_pb2_grpc.py','server.py']:
 assert pathlib.Path('/app',name).is_file(), name+' missing'
with socket.create_connection(('agent',5328),timeout=5):pass
import kv_store_pb2 as pb
import kv_store_pb2_grpc as rpc
with grpc.insecure_channel('agent:5328') as channel:
 grpc.channel_ready_future(channel).result(timeout=5)
 stub=rpc.KVStoreStub(channel)
 for key in ['cncode-verification-a','cncode-verification-b']:
  for val in [42,100,-7]:
   assert stub.SetVal(pb.SetValRequest(key=key,value=val),timeout=5).val==val
   assert stub.GetVal(pb.GetValRequest(key=key),timeout=5).val==val
print('PASS: generated bindings, real 5328 listener, gRPC Set/Get/update')
