export async function* sse(body: ReadableStream<Uint8Array>) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const x = await reader.read();
      buffer += decoder.decode(x.value, { stream: !x.done });
      buffer = buffer.replace(/\r\n/g, "\n");
      let i;
      while ((i = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, i);
        buffer = buffer.slice(i + 2);
        const data = block
          .split("\n")
          .filter((x) => x.startsWith("data:"))
          .map((x) => x.slice(5).trimStart())
          .join("\n");
        if (data && data !== "[DONE]") yield JSON.parse(data);
        if (data === "[DONE]") return;
      }
      if (x.done) {
        if (buffer.trim().startsWith("data:")) {
          const data = buffer.slice(5).trim();
          if (data && data !== "[DONE]") yield JSON.parse(data);
        }
        return;
      }
    }
  } finally {
    reader.releaseLock();
  }
}
