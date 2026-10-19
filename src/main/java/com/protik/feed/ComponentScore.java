package com.protik.feed;

/**
 * One line of an item's explanation: what a component measured and how much it added.
 * Structured data, never a sentence, so clients can word it in any language.
 *
 * @param code         the component, for example {@code RECENCY}
 * @param value        what the component measured, in [0, 1]
 * @param weight       how much the viewer's settings count this component, in [0, 1]
 * @param contribution {@code value * weight}: exactly what was added to the item's score
 */
public record ComponentScore(String code, double value, double weight, double contribution) {
}
