/**
 * Shared kernel used by every module: tenancy (college), configuration, scheduling locks and common
 * types. Registered as a shared module in {@link com.neobuddy.NeoBuddyApplication}, so it is always
 * bootstrapped in module tests.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared")
package com.neobuddy.shared;
