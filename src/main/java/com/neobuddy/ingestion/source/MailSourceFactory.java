package com.neobuddy.ingestion.source;

/** Opens the {@link MailSource} for a source inbox, using its stored credentials. */
public interface MailSourceFactory {

  MailSource open(SourceInbox inbox);
}
