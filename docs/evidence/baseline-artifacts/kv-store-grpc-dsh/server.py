"""gRPC KV store server.

Implements the KVStore service defined in kv-store.proto using an in-memory
Python dict and serves it on port 5328.
"""

from concurrent import futures

import grpc

import kv_store_pb2
import kv_store_pb2_grpc

PORT = 5328


class Server(kv_store_pb2_grpc.KVStoreServicer):
    """KVStore service implementation backed by a Python dict."""

    def __init__(self):
        self.store = {}

    def GetVal(self, request, context):
        # Unknown keys read as 0.
        return kv_store_pb2.GetValResponse(val=self.store.get(request.key, 0))

    def SetVal(self, request, context):
        self.store[request.key] = request.value
        return kv_store_pb2.SetValResponse(val=self.store[request.key])


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    kv_store_pb2_grpc.add_KVStoreServicer_to_server(Server(), server)
    server.add_insecure_port(f"[::]:{PORT}")
    server.start()
    print(f"KVStore server listening on port {PORT}", flush=True)
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
