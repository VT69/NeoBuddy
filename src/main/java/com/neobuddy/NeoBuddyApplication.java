package com.neobuddy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "NeoBuddy", sharedModules = "shared")
@SpringBootApplication
public class NeoBuddyApplication {

  public static void main(String[] args) {
    SpringApplication.run(NeoBuddyApplication.class, args);
  }
}
