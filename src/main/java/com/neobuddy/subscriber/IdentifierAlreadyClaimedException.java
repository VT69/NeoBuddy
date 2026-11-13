package com.neobuddy.subscriber;

/**
 * Another account in the same college already holds this identifier. Carries no value and says
 * nothing about who holds it.
 */
public class IdentifierAlreadyClaimedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IdentifierAlreadyClaimedException() {
    super("This identifier is already claimed by another account");
  }
}
