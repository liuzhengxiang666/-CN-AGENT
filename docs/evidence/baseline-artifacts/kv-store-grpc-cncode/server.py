"""gRPC KV store server.

Implements the KVStore service defined in /app/kv-store.proto using a plain
Python dict as the backing store.  Listens on port 5328.
"""

from concurrent import futures

import grpc

import kv_store_pb2
import kv_store_pb2_grpc

PORT = 5328


class Server(kv_store_pb2_grpc.KVStoreServicer):
    """KVStore service implementation backed by an in-memory dict."""

    def __init__(self, port=PORT):
        self.port = port
        self.store = {}
        self._server = None

    # ---- RPCs -----------------------------------------------------------
    def GetVal(self, request, context):
        """Return the value stored under key (0 if the key is unknown)."""
        val = self.store.get(request.key, 0)
        return kv_store_pb2.GetValResponse(val=val)

    def SetVal(self, request, context):
        """Store value under key and return the stored value."""
        self.store[request.key] = request.value
        return kv_store_pb2.SetValResponse(val=self.store[request.key])

    # ---- lifecycle ------------------------------------------------------
    def start(self):
        """Create, bind and start the gRPC server (non-blocking)."""
        self._server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        kv_store_pb2_grpc.add_KVStoreServicer_to_server(self, self._server)
        self._server.add_insecure_port("[::]:{}".format(self.port))
        self._server.start()
        return self._server

    def stop(self, grace=0):
        if self._server is not None:
            self._server.stop(grace)
            self._server = None

    def wait_for_termination(self):
        if self._server is not None:
            self._server.wait_for_termination()


def serve(port=PORT):
    server = Server(port=port)
    server.start()
    print("KVStore server listening on port {}".format(port), flush=True)
    server.wait_for_termination()
    return server


if __name__ == "__main__":
    serve()
