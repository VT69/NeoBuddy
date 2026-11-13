-- V1__init.sql
-- Schema from docs/ARCHITECTURE.md (Data model), plus the ShedLock table.
create extension if not exists citext;

create table college (
  id                     uuid primary key,
  slug                   text unique not null,           -- e.g. 'my-college'
  name                   text not null,
  allowed_email_domains  text[] not null,                -- sign-in allowed only from these
  sender_allowlist       text[] not null,                -- placement-cell addresses or @domains
  neo_id_pattern         text not null default '^[A-Z0-9]{8}$',
  status                 text not null default 'PILOT',  -- PILOT | ACTIVE | PAUSED
  created_at             timestamptz not null default now()
);

create table app_user (
  id            uuid primary key,
  college_id    uuid not null references college(id),
  google_sub    text unique not null,
  email         citext unique not null,
  display_name  text,
  role          text not null default 'USER',      -- USER | ADMIN
  status        text not null default 'ACTIVE',    -- ACTIVE | PAUSED
  consented_at  timestamptz not null,
  created_at    timestamptz not null default now()
);

create table identifier (
  id                uuid primary key,
  college_id        uuid not null references college(id),
  user_id           uuid not null references app_user(id) on delete cascade,
  type              text not null,                 -- NEO_ID | REG_NO | COLLEGE_EMAIL
  normalized_value  text not null,
  created_at        timestamptz not null default now(),
  unique (college_id, type, normalized_value)      -- one owner per NeoID per college
);

create table channel (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  type         text not null,                      -- DISCORD | EMAIL | NTFY
  address_enc  bytea not null,                     -- AES-GCM: Discord user id, email, ntfy topic
  verified_at  timestamptz,
  enabled      boolean not null default true,
  unique (user_id, type)
);

create table mail_source (
  id                 uuid primary key,
  college_id         uuid not null references college(id),
  email              citext unique not null,
  refresh_token_enc  bytea not null,
  last_history_id    bigint,
  status             text not null,                -- ACTIVE | AUTH_EXPIRED | DISABLED
  last_synced_at     timestamptz
);

create table processed_email (
  rfc_message_id  text primary key,                -- cross-inbox dedupe key
  college_id      uuid not null references college(id),
  source_id       uuid not null references mail_source(id),
  gmail_id        text not null,
  sender          text not null,
  subject         text,
  received_at     timestamptz not null,
  event_type      text,                            -- SHORTLIST | OFFER | OTHER
  company         text,
  status          text not null,                   -- RECEIVED | PROCESSED | FAILED
  error           text,
  processed_at    timestamptz
);

create table match_hit (
  id               uuid primary key,
  user_id          uuid not null references app_user(id) on delete cascade,
  email_id         text not null references processed_email(rfc_message_id),
  identifier_id    uuid not null references identifier(id) on delete cascade,
  event_type       text not null,
  context_snippet  text,                           -- the user's own row only
  created_at       timestamptz not null default now(),
  unique (user_id, email_id)
);

create table delivery (
  id               uuid primary key,
  match_id         uuid not null references match_hit(id) on delete cascade,
  channel_id       uuid not null references channel(id) on delete cascade,
  status           text not null default 'PENDING', -- PENDING | RETRY | SENT | DEAD
  attempts         int not null default 0,
  next_attempt_at  timestamptz not null default now(),
  last_error       text,
  sent_at          timestamptz,
  unique (match_id, channel_id)
);
create index delivery_due_idx on delivery (next_attempt_at) where status in ('PENDING', 'RETRY');

create table link_code (
  code          text primary key,                  -- Discord /link code or hashed email OTP
  user_id       uuid not null references app_user(id) on delete cascade,
  channel_type  text not null,
  attempts      int not null default 0,
  expires_at    timestamptz not null
);

-- ShedLock: one row per scheduled job, so only one instance runs it at a time.
create table shedlock (
  name        varchar(64) primary key,
  lock_until  timestamp not null,
  locked_at   timestamp not null,
  locked_by   varchar(255) not null
);
