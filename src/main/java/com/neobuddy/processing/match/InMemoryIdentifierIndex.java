package com.neobuddy.processing.match;

import com.neobuddy.processing.IdentifierSource;
import com.neobuddy.processing.IdentifierSource.IndexedIdentifier;
import com.neobuddy.processing.IdentifiersChanged;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The identifier index, held in memory as {@code college -> token -> identifiers}.
 *
 * <p>A rebuild loads everything through {@link IdentifierSource}, builds a complete new immutable
 * map, and swaps it in with one atomic write. Readers therefore always see either the whole old
 * index or the whole new one, without locks. A failed rebuild keeps the old index.
 *
 * <p>It is rebuilt when identifiers change ({@link IdentifiersChanged}, after the change commits)
 * and on a fixed schedule as a safety net. The schedule is deliberately not behind ShedLock: every
 * instance has its own copy and must refresh it.
 */
@Component
public class InMemoryIdentifierIndex implements IdentifierIndex {

  private static final Logger log = LoggerFactory.getLogger(InMemoryIdentifierIndex.class);

  private final Optional<IdentifierSource> source;
  private final AtomicReference<Map<UUID, Map<String, Set<IdentifierRef>>>> snapshot =
      new AtomicReference<>(Map.of());
  private final AtomicBoolean warnedNoSource = new AtomicBoolean();

  public InMemoryIdentifierIndex(Optional<IdentifierSource> source) {
    this.source = source;
  }

  @Override
  public Set<IdentifierRef> lookup(UUID collegeId, String token) {
    Map<String, Set<IdentifierRef>> college = snapshot.get().get(collegeId);
    if (college == null) {
      return Set.of();
    }
    Set<IdentifierRef> refs = college.get(token);
    return refs == null ? Set.of() : refs;
  }

  @Override
  public void rebuild() {
    IdentifierSource identifiers = source.orElse(null);
    if (identifiers == null) {
      if (warnedNoSource.compareAndSet(false, true)) {
        log.warn("No IdentifierSource is registered; the identifier index stays empty");
      }
      return;
    }
    List<IndexedIdentifier> all = identifiers.loadAll();
    Map<UUID, Map<String, Set<IdentifierRef>>> building = new HashMap<>();
    int indexed = 0;
    for (IndexedIdentifier identifier : all) {
      String key = key(identifier.normalizedValue());
      if (key.isEmpty() || identifier.collegeId() == null) {
        continue;
      }
      building
          .computeIfAbsent(identifier.collegeId(), college -> new HashMap<>())
          .computeIfAbsent(key, token -> new HashSet<>())
          .add(new IdentifierRef(identifier.identifierId(), identifier.userId()));
      indexed++;
    }
    snapshot.set(freeze(building));
    log.info("Identifier index rebuilt: {} identifiers in {} colleges", indexed, building.size());
  }

  /** Number of distinct tokens indexed for a college. */
  public int size(UUID collegeId) {
    Map<String, Set<IdentifierRef>> college = snapshot.get().get(collegeId);
    return college == null ? 0 : college.size();
  }

  @Scheduled(
      initialDelayString = "${neobuddy.matching.index-initial-delay:PT0S}",
      fixedDelayString = "${neobuddy.matching.index-refresh:PT5M}")
  void refreshOnSchedule() {
    rebuildQuietly();
  }

  /** After the change commits, so the rebuild sees it; also runs when there is no transaction. */
  @TransactionalEventListener(fallbackExecution = true)
  void onIdentifiersChanged(IdentifiersChanged event) {
    rebuildQuietly();
  }

  private void rebuildQuietly() {
    try {
      rebuild();
    } catch (RuntimeException e) {
      // Keep serving the previous index. Only the exception type: a message could carry data.
      log.error("Identifier index rebuild failed, keeping the previous index: {}", e.getClass());
    }
  }

  /**
   * The form a stored identifier takes as a token: email addresses lower-case, everything else
   * upper-case, exactly as {@link Tokenizer} emits them.
   */
  static String key(String normalizedValue) {
    if (normalizedValue == null) {
      return "";
    }
    String value = normalizedValue.strip();
    return value.indexOf('@') >= 0
        ? value.toLowerCase(Locale.ROOT)
        : value.toUpperCase(Locale.ROOT);
  }

  private static Map<UUID, Map<String, Set<IdentifierRef>>> freeze(
      Map<UUID, Map<String, Set<IdentifierRef>>> building) {
    Map<UUID, Map<String, Set<IdentifierRef>>> frozen = new HashMap<>();
    building.forEach((college, tokens) -> {
      Map<String, Set<IdentifierRef>> frozenTokens = new HashMap<>();
      tokens.forEach((token, refs) -> frozenTokens.put(token, Set.copyOf(refs)));
      frozen.put(college, Map.copyOf(frozenTokens));
    });
    return Map.copyOf(frozen);
  }
}
