package com.neobuddy.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * A {@code @SpringBootTest} backed by a real PostgreSQL. Uses the external database named by {@code
 * NEOBUDDY_TEST_DB_URL} when that is set, otherwise a Testcontainers Postgres. See {@link
 * TestDatabaseInitializer}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles(TestDatabaseInitializer.TEST_PROFILE)
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
public @interface DatabaseTest {}
