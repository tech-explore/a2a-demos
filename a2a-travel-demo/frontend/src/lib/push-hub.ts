/**
 * In-memory hub for A2A push notifications (webhooks) received by this Next.js server.
 * Kept on globalThis so route modules and dev-mode reloads share one instance.
 */
import { frameTaskId } from "./a2a";

export interface PushEvent {
  id: number;
  ts: number;
  taskId?: string;
  body: unknown;
}

interface Hub {
  seq: number;
  events: PushEvent[];
  tokens: Set<string>;
  listeners: Set<(e: PushEvent) => void>;
}

const g = globalThis as unknown as { __a2aPushHub?: Hub };
const hub: Hub = (g.__a2aPushHub ??= { seq: 0, events: [], tokens: new Set(), listeners: new Set() });

const MAX_EVENTS = 500;

/** Remember a token we handed to the agent; webhook calls must echo it in X-A2A-Notification-Token. */
export function registerToken(token: string) {
  hub.tokens.add(token);
}

export function isKnownToken(token: string | null): boolean {
  return !!token && hub.tokens.has(token);
}

export function publish(body: unknown): PushEvent {
  const event: PushEvent = { id: ++hub.seq, ts: Date.now(), taskId: frameTaskId(body), body };
  hub.events.push(event);
  if (hub.events.length > MAX_EVENTS) hub.events.shift();
  for (const listener of hub.listeners) listener(event);
  return event;
}

export function history(taskId?: string | null): PushEvent[] {
  return hub.events.filter((e) => !taskId || e.taskId === taskId);
}

export function subscribe(listener: (e: PushEvent) => void): () => void {
  hub.listeners.add(listener);
  return () => hub.listeners.delete(listener);
}
