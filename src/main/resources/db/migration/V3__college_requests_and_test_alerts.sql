-- T9: students of colleges NeoBuddy does not serve yet can ask for theirs.
-- Not tenant data: the college does not exist yet. Holds no account, only what the form asks for.
create table college_request (
  id             uuid primary key,
  college_name   text not null,
  email_domain   text not null,
  contact_email  citext,
  created_at     timestamptz not null default now()
);
create index college_request_created_idx on college_request (created_at);

-- T9: a test alert is a synthetic email and match flagged as a test, so it goes through the
-- normal delivery pipeline. A test email comes from no source inbox; every real one still does.
alter table processed_email add column is_test boolean not null default false;
alter table processed_email alter column source_id drop not null;
alter table processed_email add constraint processed_email_source_required
  check (is_test or source_id is not null);

alter table match_hit add column is_test boolean not null default false;
