package com.neobuddy.ingestion;

import java.util.UUID;

/**
 * Published when Google no longer accepts a source inbox's refresh token (revoked or expired). The
 * source has been set to AUTH_EXPIRED and is not polled again until it is reconnected.
 *
 * @param sourceId the source inbox
 * @param collegeId its college
 */
public record SourceAuthExpired(UUID sourceId, UUID collegeId) {}
