package org.xplore.a2ademos.springsupport;

/**
 * @author Murali Krishna Punaganti Venkata
 * Created at: 14/10/2026(Wednesday) 15:30
 */

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deliberately simple, regex-based trip parser so the demo needs no LLM or API key.
 * Every agent uses it to read the natural-language text it receives.
 */
public class TripRequest {

  private static final Pattern DEST = Pattern.compile("\\bto\\s+([A-Z][A-Za-z]+(?:\\s[A-Z][A-Za-z]+)?)");
  private static final Pattern ORIGIN = Pattern.compile("\\bfrom\\s+([A-Z][A-Za-z]+(?:\\s[A-Z][A-Za-z]+)?)");
  private static final Pattern DAYS = Pattern.compile("(\\d+)[\\s-]*(?:day|days|night|nights)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern TRAVELLERS = Pattern.compile("(\\d+)\\s*(?:people|persons|travell?ers|adults|guests)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern BUDGET_SYMBOL = Pattern.compile("([$€£])\\s?(\\d[\\d,]*)");
  private static final Pattern BUDGET_CODE = Pattern.compile("(\\d[\\d,]*)\\s?(USD|EUR|GBP|euros?|dollars?|pounds?)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern BUDGET_WORD = Pattern.compile("budget\\D{0,12}(\\d[\\d,]*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern ANSWER = Pattern.compile("(?i)user answer:\\s*(.+)$", Pattern.DOTALL);
  private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d[\\d,]*)\\s*$");

  public String origin;
  public String destination;
  public Integer days;
  public Integer travellers;
  public Integer budget;
  public String currency;
  public boolean simulateFailure;
  /** The user's reply to a question a sub-agent asked (relayed by the orchestrator), if any. */
  public String answer;

  public static TripRequest parse(String text) {
    TripRequest t = new TripRequest();
    if (text == null) {
      return t;
    }
    Matcher m = DEST.matcher(text);
    if (m.find()) t.destination = m.group(1).trim();
    m = ORIGIN.matcher(text);
    if (m.find()) t.origin = m.group(1).trim();
    m = DAYS.matcher(text);
    if (m.find()) t.days = Integer.parseInt(m.group(1));
    m = TRAVELLERS.matcher(text);
    if (m.find()) t.travellers = Integer.parseInt(m.group(1));

    m = BUDGET_SYMBOL.matcher(text);
    if (m.find()) {
      t.budget = number(m.group(2));
      t.currency = switch (m.group(1)) { case "$" -> "USD"; case "£" -> "GBP"; default -> "EUR"; };
    } else if ((m = BUDGET_CODE.matcher(text)).find()) {
      t.budget = number(m.group(1));
      t.currency = currency(m.group(2));
    } else if ((m = BUDGET_WORD.matcher(text)).find()) {
      t.budget = number(m.group(1));
    } else if ((m = BARE_NUMBER.matcher(text)).find()) {
      t.budget = number(m.group(1)); // a bare "3000" as an answer to "what's your budget?"
    }
    t.simulateFailure = text.toLowerCase().contains("simulate-failure");
    m = ANSWER.matcher(text);
    if (m.find()) t.answer = m.group(1).trim();
    return t;
  }

  /** Fill in anything missing from an earlier turn of the same conversation. */
  public TripRequest mergeOver(TripRequest older) {
    if (older == null) return this;
    if (origin == null) origin = older.origin;
    if (destination == null) destination = older.destination;
    if (days == null) days = older.days;
    if (travellers == null) travellers = older.travellers;
    if (budget == null) budget = older.budget;
    if (currency == null) currency = older.currency;
    simulateFailure = simulateFailure || older.simulateFailure;
    return this;
  }

  public TripRequest withDefaults() {
    if (origin == null) origin = "Berlin";
    if (days == null) days = 5;
    if (travellers == null) travellers = 1;
    if (currency == null) currency = "EUR";
    return this;
  }

  /** The canonical sentence the orchestrator sends to sub-agents (round-trips through parse()). */
  public String toSentence() {
    return "Trip to " + destination + " from " + origin + ", " + days + " days, " + travellers
      + " travellers, budget " + budget + " " + currency + (simulateFailure ? " simulate-failure" : "");
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("origin", origin);
    map.put("destination", destination);
    map.put("days", days);
    map.put("travellers", travellers);
    map.put("budget", budget);
    map.put("currency", currency);
    return map;
  }

  private static Integer number(String s) {
    return Integer.parseInt(s.replace(",", ""));
  }

  private static String currency(String s) {
    String u = s.toUpperCase();
    if (u.startsWith("DOLLAR")) return "USD";
    if (u.startsWith("EURO")) return "EUR";
    if (u.startsWith("POUND")) return "GBP";
    return u;
  }
}
