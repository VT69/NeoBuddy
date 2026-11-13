-- Spring Modulith event publication registry (spring-modulith-events-jpa 2.1).
-- Kept in Flyway rather than created by Hibernate so that every table has one owner and
-- Hibernate can stay on ddl-auto=validate. Mirrors Modulith's reference PostgreSQL schema.
-- If spring.modulith.events.completion-mode=archive is ever enabled, add a matching
-- event_publication_archive table in a new migration.
create table event_publication (
  id                      uuid primary key,
  listener_id             text not null,
  event_type              text not null,
  serialized_event        text not null,
  publication_date        timestamptz not null,
  completion_date         timestamptz,
  status                  text,
  completion_attempts     int not null default 0,
  last_resubmission_date  timestamptz
);
create index event_publication_serialized_event_hash_idx
  on event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx
  on event_publication (completion_date);
