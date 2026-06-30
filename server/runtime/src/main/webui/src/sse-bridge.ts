// sse-bridge.ts
// SSE connection manager dispatching pages-event CustomEvents.
// Replaces DebateEventBus singleton with standard casehub-pages event pattern.

let eventSource: EventSource | null = null;
let currentSessionId: string | null = null;

export function connectSSE(sessionId: string): void {
  if (currentSessionId === sessionId && eventSource) return;
  disconnectSSE();
  currentSessionId = sessionId;
  eventSource = new EventSource(`/api/debate/${encodeURIComponent(sessionId)}/events`);

  eventSource.onmessage = (msg) => {
    let data: unknown;
    try { data = JSON.parse(msg.data); } catch { return; }
    if (data === "heartbeat" || (data as any)?.type === "heartbeat") return;

    if (Array.isArray(data)) {
      document.dispatchEvent(new CustomEvent("pages-event", {
        bubbles: true, composed: true,
        detail: { topic: "debate-entries", payload: data },
      }));
    } else if ((data as any).type) {
      document.dispatchEvent(new CustomEvent("pages-event", {
        bubbles: true, composed: true,
        detail: { topic: (data as any).type, payload: data },
      }));
    }
  };

  eventSource.onerror = () => {
    document.dispatchEvent(new CustomEvent("pages-event", {
      bubbles: true, composed: true,
      detail: { topic: "sse-reconnect", payload: {} },
    }));
  };
}

export function disconnectSSE(): void {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  currentSessionId = null;
}

export function getSessionId(): string | null {
  return currentSessionId;
}
