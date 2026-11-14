package com.neobuddy.web.admin;

import java.util.List;

/** An admin form was not saved; each message names a field and never echoes its value. */
public class AdminFormException extends RuntimeException {

  private final List<String> errors;

  public AdminFormException(List<String> errors) {
    super(String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  public List<String> errors() {
    return errors;
  }
}
