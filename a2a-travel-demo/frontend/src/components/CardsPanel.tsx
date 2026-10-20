"use client";

/* eslint-disable @typescript-eslint/no-explicit-any */
import { useCallback, useEffect, useState } from "react";
import { agentMeta } from "@/lib/agents";

interface CardInfo {
  name: string;
  url: string;
  online: boolean;
  card: any;
}

export default function CardsPanel({ focus }: { focus: string | null }) {
  const [cards, setCards] = useState<CardInfo[]>([]);
  const [raw, setRaw] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setCards(await (await fetch("/api/cards", { cache: "no-store" })).json());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (focus) setRaw(focus);
  }, [focus]);

  return (
    <div className="cards">
      <div className="toolbar">
        <span className="muted small">
          Discovery: <code>GET /.well-known/agent-card.json</code>
        </span>
        <span style={{ flex: 1 }} />
        <button className="btn ghost" onClick={load} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>
      {cards.map((c) => {
        const meta = agentMeta(c.name);
        const card = c.card;
        return (
          <div key={c.name} className={`card ${focus === c.name ? "focus" : ""}`}>
            <div className="card-head">
              <span className="card-title" style={{ color: meta?.color }}>
                {meta?.emoji} {card?.name ?? meta?.label}
              </span>
              <span className={`pill ${c.online ? "ok" : "bad"}`}>{c.online ? "online" : "offline"}</span>
            </div>
            {card ? (
              <>
                <p className="muted">{card.description}</p>
                <div className="badges">
                  <span className="badge">v{card.version}</span>
                  {card.capabilities?.streaming && <span className="badge">streaming</span>}
                  {card.capabilities?.pushNotifications && <span className="badge">push</span>}
                  {(card.supportedInterfaces ?? []).map((i: any, idx: number) => (
                    <span key={idx} className="badge">
                      {i.protocolBinding} · {i.url}
                    </span>
                  ))}
                </div>
                {(card.skills ?? []).map((s: any) => (
                  <div key={s.id} className="skill">
                    <b>{s.name}</b> <span className="muted">({s.id})</span>
                    <div className="muted small">{s.description}</div>
                  </div>
                ))}
                <button className="btn ghost" onClick={() => setRaw(raw === c.name ? null : c.name)}>
                  {raw === c.name ? "Hide" : "Show"} raw JSON
                </button>
                {raw === c.name && <pre className="json">{JSON.stringify(card, null, 2)}</pre>}
              </>
            ) : (
              <p className="muted">Could not fetch {c.url}/.well-known/agent-card.json</p>
            )}
          </div>
        );
      })}
    </div>
  );
}
