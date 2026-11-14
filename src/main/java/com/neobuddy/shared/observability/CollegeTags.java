package com.neobuddy.shared.observability;

import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The college slug for a college id, for metric tags and log context, without a query per event.
 * The admin console calls {@link #forget} after renaming a college.
 */
@Component
public class CollegeTags {

  private final CollegeRepository colleges;
  private final Map<UUID, String> slugs = new ConcurrentHashMap<>();

  CollegeTags(CollegeRepository colleges) {
    this.colleges = colleges;
  }

  /**
   * @return the slug, or {@link Metrics#UNKNOWN} for null or a college that does not exist
   */
  public String slugOf(UUID collegeId) {
    if (collegeId == null) {
      return Metrics.UNKNOWN;
    }
    String cached = slugs.get(collegeId);
    if (cached != null) {
      return cached;
    }
    String slug = colleges.findById(collegeId).map(College::getSlug).orElse(null);
    if (slug == null) {
      return Metrics.UNKNOWN;
    }
    slugs.put(collegeId, slug);
    return slug;
  }

  public void forget(UUID collegeId) {
    slugs.remove(collegeId);
  }
}
