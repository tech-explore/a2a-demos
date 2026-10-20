package org.xplore.a2ademos.springsupport;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 14/10/2026(Wednesday) 15:30
 */

/** One observable thing that happened on an agent: a raw JSON-RPC message, an SSE frame, a delegation... */
public record TraceEvent(
  long seq,
  long ts,
  String agent,
  String kind,        // rpc-request | rpc-response | sse-frame | delegation | client-event | note
  String direction,   // in | out
  String method,      // JSON-RPC method, when known
  String peer,        // the other agent involved, when known
  String payload) {   // raw JSON (or short text)
}
