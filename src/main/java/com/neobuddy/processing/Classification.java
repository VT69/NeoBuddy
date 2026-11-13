package com.neobuddy.processing;

import java.util.Optional;

/**
 * The outcome of classifying an email.
 *
 * @param type shortlist, offer or other
 * @param company best guess at the recruiting company; empty only when the subject is blank
 */
public record Classification(EventType type, Optional<String> company) {}
