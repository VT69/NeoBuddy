-- T11: admin console.

-- Per-college classifier overrides, edited in the admin console. An empty list inherits the
-- default (application.yml); a non-empty one replaces it for this college. Recruiters are added to
-- the global list.
alter table college add column classifier_offer_subject     text[] not null default '{}';
alter table college add column classifier_offer_body        text[] not null default '{}';
alter table college add column classifier_shortlist_subject text[] not null default '{}';
alter table college add column classifier_shortlist_body    text[] not null default '{}';
alter table college add column known_recruiters             text[] not null default '{}';

-- A refresh token can only be refreshed with the OAuth client that issued it. Inboxes authorised
-- with ./gradlew authorizeSource use the "Desktop app" client; inboxes connected through the admin
-- console's invite link use the "Web application" client.
alter table mail_source add column oauth_client text not null default 'DESKTOP';
alter table mail_source add constraint mail_source_oauth_client_known
  check (oauth_client in ('DESKTOP', 'WEB'));

-- Single-use, expiring links that let a campus lead connect their college's inbox. Only a SHA-256
-- hash of the link's token is stored, so the table alone cannot be used to connect an inbox.
create table source_invite (
  id          uuid primary key,
  college_id  uuid not null references college(id),
  token_hash  text unique not null,
  created_by  uuid references app_user(id) on delete set null,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,
  used_at     timestamptz,
  source_id   uuid references mail_source(id)
);
create index source_invite_college_idx on source_invite (college_id, created_at);

-- Dead deliveries an admin chose not to retry. Kept, not deleted, so a reprocessed email does not
-- queue the same alert again.
comment on column delivery.status is 'PENDING | RETRY | SENT | DEAD | DISCARDED';

-- The admin console lists recent emails newest first, per college.
create index processed_email_received_idx on processed_email (college_id, received_at desc);
create index delivery_dead_idx on delivery (status) where status = 'DEAD';
