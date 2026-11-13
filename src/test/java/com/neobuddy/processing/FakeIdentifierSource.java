package com.neobuddy.processing;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The subscriber module's side of the {@link IdentifierSource} port, faked in memory. */
public final class FakeIdentifierSource implements IdentifierSource {

  private final List<IndexedIdentifier> identifiers = new CopyOnWriteArrayList<>();
  private volatile RuntimeException failure;

  /** Registers an identifier and returns its new id. */
  public UUID add(UUID collegeId, UUID userId, String normalizedValue) {
    UUID identifierId = UUID.randomUUID();
    identifiers.add(new IndexedIdentifier(collegeId, userId, identifierId, normalizedValue));
    return identifierId;
  }

  public void addAll(List<IndexedIdentifier> more) {
    identifiers.addAll(more);
  }

  public void clear() {
    identifiers.clear();
  }

  /** Makes every later {@link #loadAll()} throw, like a database outage. */
  public void failWith(RuntimeException exception) {
    this.failure = exception;
  }

  @Override
  public List<IndexedIdentifier> loadAll() {
    if (failure != null) {
      throw failure;
    }
    return List.copyOf(identifiers);
  }
}
