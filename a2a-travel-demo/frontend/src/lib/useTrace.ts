"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { TraceEvent } from "./a2a";

const MAX_EVENTS = 2000;

/** Subscribes to the merged trace stream of all agents (see app/api/trace/route.ts). */
export function useTrace() {
  const [events, setEvents] = useState<TraceEvent[]>([]);
  const [online, setOnline] = useState<Record<string, boolean>>({});
  const seen = useRef(new Set<string>());

  useEffect(() => {
    const source = new EventSource("/api/trace");

    source.onmessage = (e) => {
      let ev: TraceEvent;
      try {
        ev = JSON.parse(e.data) as TraceEvent;
      } catch {
        return;
      }
      const key = `${ev.agent}:${ev.seq}`;
      if (seen.current.has(key)) return; // replays after reconnect
      seen.current.add(key);
      setEvents((prev) => {
        const next = [...prev, ev];
        if (prev.length && ev.ts < prev[prev.length - 1].ts) next.sort((a, b) => a.ts - b.ts || a.seq - b.seq);
        return next.length > MAX_EVENTS ? next.slice(-MAX_EVENTS) : next;
      });
    };

    source.addEventListener("agent-status", (e) => {
      try {
        const { agent, online: isOnline } = JSON.parse((e as MessageEvent).data);
        setOnline((prev) => ({ ...prev, [agent]: isOnline }));
      } catch {
        /* ignore */
      }
    });

    return () => source.close();
  }, []);

  const clearAll = useCallback(() => setEvents([]), []);
  return { events, online, clearAll };
}
