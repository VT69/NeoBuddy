# Render runbook (free tier)

A zero-cost way to run NeoBuddy: one free Render web service built from the repository's
`Dockerfile`, a free Supabase PostgreSQL, Brevo for email. The AWS path (`deploy/README.md`,
Terraform, Jib, `.github/workflows/deploy.yml`) is untouched and stays available; see
"Moving back to AWS" at the end.

```
browser / Google / UptimeRobot --https--> Render edge --> web service "neobuddy" (Docker, profile render)
                                                          | 512 MB, 0.1 CPU, sleeps after 15 min idle
                                                          +--TLS--> Supabase session pooler :5432
                                                          +--https--> Brevo API, ntfy.sh, Discord, Gmail API
```

| | AWS (`deploy/README.md`) | Render (this file) |
| --- | --- | --- |
| Compute | EC2 t4g.small, always on | Render free web service; sleeps without inbound traffic |
| Database | Postgres container + nightly S3 backup | Supabase free (no backups you can download on the free plan) |
| HTTPS | Caddy + your domain | Render, `https://<name>.onrender.com` |
| Secrets | SSM Parameter Store | Environment variables in the Render dashboard |
| Email | Amazon SES (`provider: ses`) | Brevo HTTPS API (`provider: brevo`; SMTP is blocked on Render free) |
| Image | Jib, pushed to ECR by CI | `Dockerfile`, built by Render |
| Spring profile | default | `render` (`src/main/resources/application-render.yml`) |

What the `render` profile changes: Postgres always with `sslmode=require` and a pool of 3;
Google sign-in redirects pinned to the public https address; email through Brevo; deliveries
dispatched right after an email's matches commit, with the scheduled dispatcher only a sweep every
2 minutes (instead of a database query every 10 seconds); one attachment parsed at a time.

## Limits to know

- **Sleep.** Render stops a free service after 15 minutes without inbound HTTP traffic. While it
  sleeps nothing polls Gmail, the Discord bot is offline and no alert goes out. Outbound traffic
  (Gmail polls, the Discord gateway) does not keep it awake. The keep-alive pinger below does.
- **Cold start.** Waking takes Render's own spin-up plus about 80 seconds of JVM startup (measured
  locally with `--cpus=0.1 --memory=512m`: 83 s to healthy with the class-data archive, 124 s
  without; 258 MiB in use after startup).
- **Hours.** A workspace gets 750 free instance hours a month: enough for this one service
  awake around the clock, not for a second one.
- **Brevo free plan:** a daily sending cap (300 emails a day when this was written). One shortlist
  can match many students; once the cap is hit, email deliveries retry for about half an hour and
  then go `DEAD`. Push and Discord are unaffected. Treat email as the backup channel.
- **No downloadable backups** on Supabase's free plan. Take your own `pg_dump` now and then (see
  "Moving back to AWS" for the command).

## 1. Supabase

1. Create a project at supabase.com, region **Southeast Asia (Singapore)**, the same as the
   Render service. Save the database password.
2. Project, **Connect**, **Session pooler** (not "Direct connection": that host is IPv6-only on
   the free plan and Render cannot reach it; not "Transaction pooler" on port 6543: it breaks
   prepared statements and Flyway's lock). It shows something like
   `postgresql://postgres.abcdefghijkl:[YOUR-PASSWORD]@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres`.
3. Turn it into the three variables:

   | Variable | Value |
   | --- | --- |
   | `DB_URL` | `jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres` |
   | `DB_USERNAME` | `postgres.abcdefghijkl` (with the project ref) |
   | `DB_PASSWORD` | the database password |

   `sslmode=require` is added by the profile; it does not need to be in the URL.

Flyway creates every table at the first start. Nothing has to be run by hand. The poller queries
the database every 2 minutes while the service is awake, which also keeps Supabase from pausing
the project for inactivity.

## 2. Brevo

1. Create a free account at brevo.com.
2. **Senders, domains & dedicated IPs**, **Senders**: add the address alerts should come from and
   confirm it from its inbox. One verified sender is enough. A free-mail address (Gmail, Outlook)
   works but often lands in spam because Brevo cannot sign for that domain; a domain of your own,
   authenticated in Brevo, does better.
3. **SMTP & API**, **API keys**: create a key (`xkeysib-...`). This is `NEOBUDDY_BREVO_API_KEY`.
4. **Security**, **Authorised IPs**: switch IP blocking **off**. Render's outbound addresses
   change, and with blocking on Brevo answers `401` and every email stays in `RETRY`.

`NEOBUDDY_BREVO_FROM` is the verified sender, optionally with a name: `NeoBuddy <you@example.com>`.

## 3. Google sign-in

In Google Cloud Console, **APIs & Services**, **Credentials**, the **neobuddy-login** Web client
(README, "Google sign-in"), add:

| Field | Value |
| --- | --- |
| Authorised JavaScript origins | `https://<name>.onrender.com` |
| Authorised redirect URIs | `https://<name>.onrender.com/login/oauth2/code/google` |

`<name>` is the service name Render assigns (the Blueprint asks for `neobuddy`; Render adds a
suffix if that name is taken; the dashboard shows the final address). Keep the AWS domain's URIs
too if you plan to go back. The Gmail source client ("Desktop app") needs no change: its token is
issued once on your own machine with `./gradlew authorizeSource`.

## 4. Deploy the Blueprint

1. Render dashboard, **New**, **Blueprint**, connect the GitHub repository. Render reads
   `render.yaml`: one free Docker web service in Singapore, health check `/actuator/health`,
   auto-deploy of `main` after its GitHub checks pass.
2. Render asks for every variable marked `sync: false`:

   | Variable | Required | Value |
   | --- | --- | --- |
   | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes | step 1 |
   | `NEOBUDDY_ENCRYPTION_KEY` | yes | `openssl rand -base64 32`. **Keep a copy elsewhere**: the data is unreadable without it, here and after a move to AWS. |
   | `NEOBUDDY_GOOGLE_CLIENT_ID`, `NEOBUDDY_GOOGLE_CLIENT_SECRET` | yes | the neobuddy-login client |
   | `NEOBUDDY_BREVO_API_KEY`, `NEOBUDDY_BREVO_FROM` | yes | step 2 |
   | `NEOBUDDY_GMAIL_CLIENT_ID`, `NEOBUDDY_GMAIL_CLIENT_SECRET` | for ingestion | README, "Connect a source inbox" |
   | `NEOBUDDY_BOOTSTRAP_*` | first start | README, "Bootstrap". Further optional ones (`..._ADMIN_REG_NO`, `..._ADMIN_NTFY_TOPIC`, `..._ADMIN_PERSONAL_EMAIL`, `..._COLLEGE_NEO_ID_PATTERN`) can be added under **Environment** later. |
   | `DISCORD_TOKEN`, `NEOBUDDY_DISCORD_GUILD_ID`, `NEOBUDDY_DISCORD_OPS_CHANNEL_ID`, `NEOBUDDY_DISCORD_INVITE_URL` | no | deploy/README.md, section 9. Leave `DISCORD_TOKEN` empty to run without the bot. |
   | `NEOBUDDY_CONTACT_EMAIL` | no | shown on the privacy policy |

   Set by the Blueprint or by Render itself, nothing to enter: `SPRING_PROFILES_ACTIVE=render`,
   `PORT`, `RENDER_EXTERNAL_URL` (used for the Google redirect). `NEOBUDDY_PUBLIC_URL` overrides
   that address, for example with a custom domain later. `JDK_JAVA_OPTIONS` overrides the JVM flags
   in the `Dockerfile`, which explains each of them.
3. **Apply**. The first build takes several minutes (Gradle downloads its dependencies); the
   service is live when the health check passes.

## 5. Check it

```bash
curl -fsS https://<name>.onrender.com/actuator/health
```

Expect `{"groups":["liveness","readiness"],"status":"UP"}` over https. Then check that sign-in
starts with the https address:

```bash
curl -sS -o /dev/null -w '%{redirect_url}\n' https://<name>.onrender.com/oauth2/authorization/google
```

The printed Google URL must contain
`redirect_uri=https%3A%2F%2F<name>.onrender.com%2Flogin%2Foauth2%2Fcode%2Fgoogle`. Finally sign in,
add an email channel and send a test alert from the dashboard: it should arrive within seconds.

## 6. Keep-alive pinger

Without inbound traffic the service sleeps after 15 minutes, and with it Gmail polling and the
Discord bot. Ping the health endpoint every 10 minutes from outside, with either:

- **UptimeRobot** (uptimerobot.com): New monitor, type HTTP(s), URL
  `https://<name>.onrender.com/actuator/health`, interval 10 minutes. You also get an email when
  the service is down.
- **cron-job.org**: New cronjob, same URL, every 10 minutes (`*/10 * * * *`), request method GET.
  Under notifications, enable "failure" to hear about outages.

Every ping runs a database health check, which is cheap; it is also what keeps the service awake.
After a deploy or a restart the first ping may see the 80-second cold start; set the monitor's
timeout to at least 60 seconds or tolerate one failure.

## 7. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Deploy never becomes healthy; logs show `Connection ... refused` or `Network is unreachable` | `DB_URL` points at the direct host (IPv6). Use the session pooler, step 1. |
| `FATAL: Tenant or user not found` | `DB_USERNAME` lacks the project ref: `postgres.<ref>`. |
| `prepared statement "S_1" already exists` | Port 6543 (transaction pooler). Use 5432. |
| Startup fails naming `NEOBUDDY_BREVO_API_KEY` or `NEOBUDDY_BREVO_FROM` | Set both, or `NEOBUDDY_EMAIL_PROVIDER=log` to send no email at all. |
| Emails stay in `RETRY` with `Brevo HTTP 401` | Wrong key, or Brevo's IP blocking is on (step 2.4). |
| `Brevo HTTP 402` | Out of free credits for today. |
| Google says `redirect_uri_mismatch` | The URIs of step 3 do not match the service's address exactly. |
| Redirects or links use `http://` | Render's proxy reached the app from an address Tomcat does not treat as internal. Set `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` to a regular expression matching it (`.*` trusts every sender of `X-Forwarded-*`, acceptable only because the service is reachable through Render's edge alone). |
| Service restarts with `Terminating due to java.lang.OutOfMemoryError` | A very large attachment. Lower `-XX:MaxRAMPercentage` in `JDK_JAVA_OPTIONS` if the kernel killed it instead (exit 137), or raise it if the Java heap ran out; the service has 512 MB in total. |

## Moving back to AWS

Nothing of the AWS path was removed, so going back is data plus configuration:

1. Build the AWS side as in `deploy/README.md`, sections 1 to 5, but do not deploy yet.
2. In SSM (`deploy/README.md`, section 4) use the **same** `NEOBUDDY_ENCRYPTION_KEY` as on Render;
   copy the other values from the Render dashboard. Email goes back to SES: `NEOBUDDY_SES_FROM`
   with a verified identity; the Brevo variables are not needed (the default profile uses SES).
3. Stop changes on Render: suspend the service (Settings, **Suspend**) and pause the pinger.
4. Dump the data from Supabase (public schema only; Supabase's own schemas stay behind):

   ```bash
   pg_dump --no-owner --no-privileges --schema=public \
     "postgresql://postgres.<ref>:<password>@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require" \
     | gzip > neobuddy.sql.gz
   ```

   `pg_dump` must be at least the Supabase server's major version. If that is newer than the
   PostgreSQL 16 on EC2, strip settings 16 does not know before restoring, for example
   `gunzip -c neobuddy.sql.gz | grep -v '^SET transaction_timeout' | gzip > fixed.sql.gz`.
5. Upload the dump to the backup bucket and restore it as in `deploy/README.md`, section 8.
6. Deploy (`deploy/README.md`, section 6). Flyway sees the schema is already current.
7. Point Google sign-in at the AWS domain (redirect URI `https://<domain>/login/oauth2/code/google`)
   and check `/actuator/health` there. Delete the Render service and the Supabase project once
   you are satisfied.
