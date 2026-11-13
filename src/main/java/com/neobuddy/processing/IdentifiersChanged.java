package com.neobuddy.processing;

import java.util.UUID;

/**
 * Published by the subscriber module whenever identifiers are added, removed, paused or resumed, so
 * the in-memory index is rebuilt at once instead of at the next scheduled refresh.
 *
 * <p>It lives here, next to {@link IdentifierSource}, because the subscriber module already depends
 * on processing to implement that port; defining the event in subscriber would make the two modules
 * depend on each other.
 *
 * @param collegeId the college whose identifiers changed
 */
public record IdentifiersChanged(UUID collegeId) {}
