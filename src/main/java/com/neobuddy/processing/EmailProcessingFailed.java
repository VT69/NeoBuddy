package com.neobuddy.processing;

import java.util.UUID;

/**
 * Published when processing an ingested email failed and it was marked FAILED. Carries ids and the
 * exception's class name only; never content.
 *
 * @param rfcMessageId RFC 822 Message-ID of the email; the correlation id
 * @param errorClass the failure's exception class
 */
public record EmailProcessingFailed(UUID collegeId, String rfcMessageId, String errorClass) {}
