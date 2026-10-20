"use client";

/* eslint-disable @typescript-eslint/no-explicit-any */
export default function TripPlan({ plan }: { plan: any }) {
  const trip = plan?.trip ?? {};
  const currency = trip.currency ?? "EUR";
  const flights: any[] = plan?.flights?.options ?? [];
  const hotels: any[] = plan?.hotels?.options ?? [];
  const days: any[] = plan?.activities?.plan ?? [];
  const total = Number(plan?.estimatedTotal ?? 0);
  const budget = Number(trip.budget ?? 0);
  const pct = budget ? Math.min(100, Math.round((total / budget) * 100)) : 0;

  return (
    <div className="plan">
      <div className="plan-head">
        <div>
          <div className="plan-title">
            {trip.origin} → {trip.destination}
          </div>
          <div className="muted">
            {trip.days} days · {trip.travellers} traveller(s)
          </div>
        </div>
        <div className={`pill ${plan?.withinBudget ? "ok" : "bad"}`}>{plan?.withinBudget ? "within budget" : "over budget"}</div>
      </div>

      <div className="budget">
        <div className="budget-bar">
          <div className={`budget-fill ${plan?.withinBudget ? "ok" : "bad"}`} style={{ width: `${pct}%` }} />
        </div>
        <div className="muted">
          {total} / {budget} {currency} (recommended flight + hotel)
        </div>
      </div>

      <h4>✈️ Flights</h4>
      {flights.map((f, i) => (
        <div key={i} className={`row ${f.recommended ? "rec" : ""}`}>
          <span>
            {f.airline} <span className="muted">{f.flightNo}</span>
          </span>
          <span className="muted">
            {f.stops === 0 ? "non-stop" : `${f.stops} stop${f.stops > 1 ? "s" : ""}`} · {f.durationHours}h
          </span>
          <b>
            {f.total} {currency}
          </b>
        </div>
      ))}

      <h4>🏨 Hotels</h4>
      {hotels.map((h, i) => (
        <div key={i} className={`row ${h.recommended ? "rec" : ""}`}>
          <span>{h.name}</span>
          <span className="muted">
            {"★".repeat(h.stars)} · {h.rating}
          </span>
          <b>
            {h.total} {currency}
          </b>
        </div>
      ))}

      <h4>🎟️ Activities</h4>
      {plan?.activitiesAvailable === false && <div className="pill warn">Activities agent unavailable — partial plan</div>}
      {days.map((d) => (
        <div key={d.day} className="row">
          <span className="muted">Day {d.day}</span>
          <span style={{ flex: 1, textAlign: "right" }}>{(d.activities ?? []).join(" · ")}</span>
        </div>
      ))}
    </div>
  );
}
