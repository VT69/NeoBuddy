package com.neobuddy.processing.match;

import java.util.UUID;

/**
 * What the index stores for a token: which identifier it is and who owns it. Deliberately without
 * the identifier value.
 */
public record IdentifierRef(UUID identifierId, UUID userId) {}
