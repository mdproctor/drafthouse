// panels/debate-event-bus.js
// Shared SSE connection for debate events.
// Shell owns connect/disconnect; panels subscribe/unsubscribe orthogonally.

export class DebateEventBus {
  #eventSource = null;
  #sessionId = null;
  #subscribers = new Set();

  get connected() { return this.#eventSource !== null; }
  get sessionId() { return this.#sessionId; }

  connect(debateSessionId) {
    if (this.#sessionId === debateSessionId && this.#eventSource) return;
    this.disconnect();
    this.#sessionId = debateSessionId;
    this.#eventSource = new EventSource(
      '/api/debate/' + encodeURIComponent(debateSessionId) + '/events'
    );
    this.#eventSource.onmessage = (e) => {
      let data;
      try { data = JSON.parse(e.data); } catch { return; }
      if (data.type === 'heartbeat') return;
      const entries = Array.isArray(data) ? data : [data];
      for (const sub of this.#subscribers) {
        try { sub.onEntries(entries); } catch (err) {
          console.error('DebateEventBus subscriber error:', err);
        }
      }
    };
    this.#eventSource.onerror = () => {
      for (const sub of this.#subscribers) {
        try { if (sub.onReconnect) sub.onReconnect(); } catch (err) {
          console.error('DebateEventBus reconnect handler error:', err);
        }
      }
    };
  }

  disconnect() {
    if (this.#eventSource) {
      this.#eventSource.close();
      this.#eventSource = null;
    }
    this.#sessionId = null;
  }

  subscribe({ onEntries, onReconnect }) {
    if (typeof onEntries !== 'function') {
      throw new Error('subscribe() requires onEntries callback');
    }
    const sub = { onEntries, onReconnect: onReconnect || null };
    this.#subscribers.add(sub);
    return () => { this.#subscribers.delete(sub); };
  }
}

export const debateEventBus = new DebateEventBus();
