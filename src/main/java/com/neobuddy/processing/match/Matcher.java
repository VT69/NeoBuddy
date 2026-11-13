package com.neobuddy.processing.match;

import com.neobuddy.processing.EventType;
import com.neobuddy.processing.MatchHit;
import com.neobuddy.processing.extract.TextChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Finds subscribers in the chunks of one email: tokenize each chunk, look every token up in the
 * index of the email's college, and keep one hit per user.
 *
 * <p>Tokens are compared whole, so {@code N1B7J5V6X} never matches {@code N1B7J5V6}. A user who
 * appears several times (by NeoID in one row and by registration number in another, or in both the
 * body and a sheet) gets a single hit, for the first chunk in which they appear. The snippet is
 * that one chunk and nothing around it.
 */
@Component
public class Matcher {

  private static final String ELLIPSIS = "…";
  private static final Comparator<IdentifierRef> BY_IDENTIFIER =
      Comparator.comparing(IdentifierRef::identifierId);

  private final Tokenizer tokenizer;
  private final IdentifierIndex index;

  public Matcher(Tokenizer tokenizer, IdentifierIndex index) {
    this.tokenizer = tokenizer;
    this.index = index;
  }

  /**
   * @param collegeId the college the email belongs to
   * @param eventType the type of the email, copied onto each hit
   * @param chunks the chunks of the email in document order
   * @return one hit per matched user, in order of first appearance
   */
  public List<MatchHit> match(UUID collegeId, EventType eventType, List<TextChunk> chunks) {
    Map<UUID, MatchHit> hitsByUser = new LinkedHashMap<>();
    List<IdentifierRef> found = new ArrayList<>();
    for (TextChunk chunk : chunks) {
      found.clear();
      for (String token : tokenizer.tokenize(chunk.text())) {
        Set<IdentifierRef> refs = index.lookup(collegeId, token);
        if (!refs.isEmpty()) {
          found.addAll(refs);
        }
      }
      if (found.isEmpty()) {
        continue;
      }
      // Tokens come from a set; sort so the chosen identifier does not depend on hash order.
      found.sort(BY_IDENTIFIER);
      for (IdentifierRef ref : found) {
        hitsByUser.computeIfAbsent(
            ref.userId(),
            user -> new MatchHit(user, ref.identifierId(), eventType, snippet(chunk.text())));
      }
    }
    return List.copyOf(hitsByUser.values());
  }

  static String snippet(String chunkText) {
    if (chunkText.length() <= MatchHit.MAX_SNIPPET_LENGTH) {
      return chunkText;
    }
    return chunkText.substring(0, MatchHit.MAX_SNIPPET_LENGTH - 1) + ELLIPSIS;
  }
}
