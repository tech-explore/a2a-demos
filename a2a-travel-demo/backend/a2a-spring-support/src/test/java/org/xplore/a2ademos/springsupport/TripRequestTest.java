package org.xplore.a2ademos.springsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TripRequestTest {

    @Test
    void parsesAFullRequest() {
        TripRequest t = TripRequest.parse("Plan a 5 day trip to Lisbon from Berlin for 2 people, budget 3000 EUR");
        assertEquals("Lisbon", t.destination);
        assertEquals("Berlin", t.origin);
        assertEquals(5, t.days);
        assertEquals(2, t.travellers);
        assertEquals(3000, t.budget);
        assertEquals("EUR", t.currency);
        assertFalse(t.simulateFailure);
    }

    @Test
    void parsesTwoWordCitiesAndCurrencySymbols() {
        TripRequest t = TripRequest.parse("Plan a trip to New York from Berlin, $2,500");
        assertEquals("New York", t.destination);
        assertEquals(2500, t.budget);
        assertEquals("USD", t.currency);
    }

    @Test
    void missingBudgetStaysNull() {
        TripRequest t = TripRequest.parse("Plan a 4 day trip to Tokyo from Berlin");
        assertEquals("Tokyo", t.destination);
        assertNull(t.budget);
    }

    @Test
    void acceptsBareNumberAsBudgetAnswer() {
        assertEquals(3500, TripRequest.parse("3500").budget);
        assertEquals(3500, TripRequest.parse("3500 EUR").budget);
    }

    @Test
    void mergesFollowUpOverEarlierTurn() {
        TripRequest first = TripRequest.parse("Plan a 4 day trip to Tokyo from Berlin");
        TripRequest merged = TripRequest.parse("3500 EUR").mergeOver(first).withDefaults();
        assertEquals("Tokyo", merged.destination);
        assertEquals("Berlin", merged.origin);
        assertEquals(4, merged.days);
        assertEquals(3500, merged.budget);
    }

    @Test
    void failureFlagAndAnswerAreParsed() {
        assertTrue(TripRequest.parse("Trip to Rome, budget 2000 EUR simulate-failure").simulateFailure);
        TripRequest t = TripRequest.parse("Trip to Madrid from Berlin, 4 days, 4 travellers, budget 6000 EUR. User answer: two double rooms");
        assertEquals("two double rooms", t.answer);
        assertEquals(4, t.travellers);
        assertEquals(6000, t.budget);
    }

    @Test
    void canonicalSentenceRoundTrips() {
        TripRequest original = TripRequest.parse("Plan a 6 day trip to Paris from Madrid for 3 people, budget 4000 EUR").withDefaults();
        TripRequest again = TripRequest.parse(original.toSentence());
        assertEquals(original.destination, again.destination);
        assertEquals(original.origin, again.origin);
        assertEquals(original.days, again.days);
        assertEquals(original.travellers, again.travellers);
        assertEquals(original.budget, again.budget);
        assertEquals(original.currency, again.currency);
    }
}
