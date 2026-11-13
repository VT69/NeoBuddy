package com.neobuddy.ingestion.source;

/** A message in one source inbox, by that inbox's own id for it. */
public record MailRef(String gmailId) {}
