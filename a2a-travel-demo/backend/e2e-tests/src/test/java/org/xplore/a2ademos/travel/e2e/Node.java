package org.xplore.a2ademos.travel.e2e;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 15/10/2026(Thursday) 10:45
 */

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;

/**
 * Minimal, null-safe JSON navigation on top of Gson (which the A2A SDK already brings).
 * The tests deliberately avoid Jackson: Spring Boot 4 moved to Jackson 3 (package tools.jackson), and a plain Gson
 * wrapper keeps the wire-level assertions independent of that.
 */
final class Node implements Iterable<Node> {

  private static final Node MISSING = new Node(JsonNull.INSTANCE);

  private final JsonElement element;

  private Node(JsonElement element) {
    this.element = element;
  }

  static Node parse(String json) {
    return new Node(JsonParser.parseString(json));
  }

  boolean has(String key) {
    return element.isJsonObject()
      && element.getAsJsonObject().has(key)
      && !element.getAsJsonObject().get(key).isJsonNull();
  }

  /** Child node, or null if absent (like Jackson's get). */
  Node get(String key) {
    return has(key) ? new Node(element.getAsJsonObject().get(key)) : null;
  }

  /** Child node, never null: a missing key yields an empty node whose accessors return defaults. */
  Node path(String key) {
    return has(key) ? get(key) : MISSING;
  }

  String asText() {
    return asText("");
  }

  String asText(String fallback) {
    return element.isJsonPrimitive() ? element.getAsString() : fallback;
  }

  int asInt() {
    try {
      return element.isJsonPrimitive() ? element.getAsInt() : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  boolean asBoolean() {
    return element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
  }

  int size() {
    if (element.isJsonArray()) return element.getAsJsonArray().size();
    if (element.isJsonObject()) return element.getAsJsonObject().size();
    return 0;
  }

  @Override
  public Iterator<Node> iterator() {
    List<Node> items = new ArrayList<>();
    if (element.isJsonArray()) {
      element.getAsJsonArray().forEach(e -> items.add(new Node(e)));
    }
    return items.iterator();
  }

  @Override
  public String toString() {
    return element.toString();
  }
}
