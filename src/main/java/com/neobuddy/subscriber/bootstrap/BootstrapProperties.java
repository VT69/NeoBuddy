package com.neobuddy.subscriber.bootstrap;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The v1 seed ({@code neobuddy.bootstrap.*}): the first college and its admin. Every value comes
 * from the environment; nothing real is ever written in a config file.
 *
 * @param college the college to create or update; nothing is seeded if its slug is blank
 * @param admin the first admin of that college; skipped if the email is blank
 */
@ConfigurationProperties(prefix = "neobuddy.bootstrap")
public record BootstrapProperties(CollegeSeed college, AdminSeed admin) {

  public BootstrapProperties {
    college = college == null ? new CollegeSeed(null, null, null, null, null) : college;
    admin = admin == null ? new AdminSeed(null, null, null, null, null) : admin;
  }

  /**
   * @param slug short unique name, for example {@code my-college}
   * @param name display name; defaults to the slug
   * @param allowedEmailDomains sign-in is allowed only from these domains
   * @param senderAllowlist placement-cell addresses, or {@code @domain} for a whole domain
   * @param neoIdPattern regular expression a normalised NeoID must match; has a default
   */
  public record CollegeSeed(
      String slug,
      String name,
      List<String> allowedEmailDomains,
      List<String> senderAllowlist,
      String neoIdPattern) {

    public CollegeSeed {
      allowedEmailDomains = allowedEmailDomains == null ? List.of() : allowedEmailDomains;
      senderAllowlist = senderAllowlist == null ? List.of() : senderAllowlist;
    }
  }

  /**
   * @param email the admin's college email; must be on one of the college's domains
   * @param neoId the admin's NeoID
   * @param regNo optional registration number
   * @param ntfyTopic optional ntfy topic to alert, registered as already verified
   * @param personalEmail optional personal email to alert, registered as already verified
   */
  public record AdminSeed(
      String email, String neoId, String regNo, String ntfyTopic, String personalEmail) {

    /** All of this is personal data: print none of it. */
    @Override
    public String toString() {
      return "AdminSeed[hidden]";
    }
  }
}
