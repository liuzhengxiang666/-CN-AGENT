"""Observe the pinned runtime's real model responses; never log prompts or keys.

The HTTP task schema exposes cumulative totals and drops DeepSeek cache fields.
Keep those aggregates as server evidence and capture raw provider usage separately.
No extraction, retrieval or storage algorithms are replaced here.
"""
import json
import os
import time
import uuid
import functools
from contextvars import ContextVar
from datetime import datetime, timezone
from importlib.metadata import version

os.environ["OPENVIKING_CONFIG_FILE"] = "/state/ov.conf"
from openviking.models.vlm.backends.openai_vlm import OpenAIVLM
from openviking.models.embedder.local_embedders import LocalDenseEmbedder
from openviking.telemetry import get_current_telemetry
from openviking.service.task_work_index import get_task_context

assert version("openviking") == "0.5.3", "Observer requires OpenViking 0.5.3"

def emit(kind, **data):
    task = get_task_context()
    telemetry = get_current_telemetry()
    record = dict(type=kind, at=datetime.now(timezone.utc).isoformat(),
                  taskId=task.task_id if task else None,
                  account=task.account_id if task else None,
                  telemetryId=telemetry.telemetry_id,
                  operation=telemetry.operation, **data)
    with open("/state/observations.jsonl", "a", encoding="utf-8") as output:
        output.write(json.dumps(record, ensure_ascii=False) + "\n")

original_usage = OpenAIVLM._update_token_usage_from_response
request_id = ContextVar("cncode_memory_request_id", default=None)
def observe_usage(self, response, duration_seconds=0.0):
    raw = getattr(response, "usage", None)
    emit("model.response", requestId=request_id.get() or str(uuid.uuid4()), model=self.model,
         durationMs=duration_seconds * 1000,
         raw=raw.model_dump() if raw is not None else None)
    return original_usage(self, response, duration_seconds)
OpenAIVLM._update_token_usage_from_response = observe_usage

def observe_request(original, asynchronous):
    if asynchronous:
        @functools.wraps(original)
        async def wrapper(self, *args, **kwargs):
            token = request_id.set(str(uuid.uuid4()))
            started = time.perf_counter()
            emit("model.start", requestId=request_id.get(), model=self.model)
            try:
                return await original(self, *args, **kwargs)
            except BaseException as error:
                emit("model.error", requestId=request_id.get(), model=self.model,
                     durationMs=(time.perf_counter()-started)*1000, raw=None,
                     errorType=type(error).__name__)
                raise
            finally:
                request_id.reset(token)
    else:
        @functools.wraps(original)
        def wrapper(self, *args, **kwargs):
            token = request_id.set(str(uuid.uuid4()))
            started = time.perf_counter()
            emit("model.start", requestId=request_id.get(), model=self.model)
            try:
                return original(self, *args, **kwargs)
            except BaseException as error:
                emit("model.error", requestId=request_id.get(), model=self.model,
                     durationMs=(time.perf_counter()-started)*1000, raw=None,
                     errorType=type(error).__name__)
                raise
            finally:
                request_id.reset(token)
    return wrapper

for method in ["get_completion", "get_vision_completion", "get_completion_async", "get_vision_completion_async"]:
    setattr(OpenAIVLM, method, observe_request(getattr(OpenAIVLM, method), method.endswith("_async")))

original_embed = LocalDenseEmbedder.embed
def observe_embed(self, text, is_query=False):
    started = time.perf_counter()
    success = False
    try:
        result = original_embed(self, text, is_query)
        success = True
        return result
    finally:
        emit("embedding.end", model=self.model_name, isQuery=is_query,
             durationMs=(time.perf_counter() - started) * 1000, success=success,
             providerTokens=None, dimension=self.get_dimension())
LocalDenseEmbedder.embed = observe_embed
emit("observer.ready", openviking=version("openviking"), llamaCpp=version("llama-cpp-python"))

from openviking_cli.server_bootstrap import main
main()
