package com.neobuddy.shared.college;

import java.util.List;

/**
 * A college's own classifier keywords, edited in the admin console. An empty list inherits the
 * default; a non-empty one replaces it for this college. Recruiters are added to the global list.
 */
public record ClassifierOverrides(
    List<String> offerSubject,
    List<String> offerBody,
    List<String> shortlistSubject,
    List<String> shortlistBody,
    List<String> knownRecruiters) {

  public static final ClassifierOverrides NONE =
      new ClassifierOverrides(List.of(), List.of(), List.of(), List.of(), List.of());

  public ClassifierOverrides {
    offerSubject = clean(offerSubject);
    offerBody = clean(offerBody);
    shortlistSubject = clean(shortlistSubject);
    shortlistBody = clean(shortlistBody);
    knownRecruiters = clean(knownRecruiters);
  }

  public boolean isEmpty() {
    return equals(NONE);
  }

  private static List<String> clean(List<String> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(v -> v != null && !v.isBlank())
            .map(String::strip)
            .distinct()
            .toList();
  }
}
