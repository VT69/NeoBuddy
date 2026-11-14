# Production runbook

NeoBuddy v0 runs on one EC2 instance in AWS Mumbai (`ap-south-1`): Docker Compose with
PostgreSQL 16, the app, and Caddy for HTTPS. Terraform in `infra/terraform` creates the AWS side;
the GitHub workflow `.github/workflows/deploy.yml` deploys every commit on `main` that passed CI.

```
GitHub Actions --(OIDC)--> deploy role --> ECR (image <git-sha>, arm64 + amd64)
      |                                     |
      +--> SSM Run Command --> EC2 t4g.small, Elastic IP, ports 80/443 only
                               caddy :443 -> app :8080 -> postgres (EBS volume)
                               app.env rendered from SSM /neobuddy/prod/*
                               nightly pg_dump | gzip -> S3 (30 days)
                               container logs -> CloudWatch /neobuddy/prod (30 days)
```

There is no SSH. Shell access is SSM Session Manager:
`aws ssm start-session --target <instance-id>` (needs the Session Manager plugin for the AWS CLI).

| What | Where |
| --- | --- |
| Compose project, scripts | `/opt/neobuddy` on the instance |
| Settings and secrets | `/etc/neobuddy/{infra,app,image}.env`, root only |
| Logs | CloudWatch log group `/neobuddy/prod`, one stream per container |
| Backups | `s3://neobuddy-prod-backups-<account-id>/daily/` |

Rough running cost, at on-demand prices and not checked against today's price list: about
US$15 a month (instance, 20 GB gp3, the public IPv4 address, small amounts of S3, ECR and
CloudWatch). SES and the DynamoDB lock table cost close to nothing at this volume.

## 1. One-time state bootstrap

Terraform keeps its state in an S3 bucket and locks it with a DynamoDB table. Both must exist
before the first `terraform init`, so they are created once by hand, with an AWS CLI profile that
has administrator rights in the account:

```bash
export AWS_REGION=ap-south-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
STATE_BUCKET=neobuddy-tfstate-$ACCOUNT_ID

aws s3api create-bucket --bucket "$STATE_BUCKET" \
  --create-bucket-configuration LocationConstraint=$AWS_REGION
aws s3api put-bucket-versioning --bucket "$STATE_BUCKET" \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "$STATE_BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
aws s3api put-public-access-block --bucket "$STATE_BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

aws dynamodb create-table --table-name neobuddy-tflock \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST
```

Then, in `infra/terraform`, copy `backend.hcl.example` to `backend.hcl` and
`terraform.tfvars.example` to `terraform.tfvars` and fill them in. Both are git-ignored.
`create_github_oidc_provider = false` is only needed if the account already has a GitHub Actions
OIDC provider (`aws iam list-open-id-connect-providers`).

## 2. Create the infrastructure

```bash
cd infra/terraform
terraform init -backend-config=backend.hcl
terraform plan -out=tfplan
terraform apply tfplan
terraform output
```

It creates: the ECR repository, the instance with its Elastic IP, security group and role, the
backup bucket, the log group, the GitHub OIDC provider and deploy role, and the SES domain
identity. The instance boots, installs Docker and the compose plugin, and waits for its first
deploy. `terraform test` checks the configuration offline (AWS is mocked); CI runs it.

The instance is protected against replacement and termination, because the database lives on its
root volume: Terraform ignores newer AMIs and changed user-data, `terraform destroy` refuses, and
termination protection is on. See section 9 for moving to a new instance on purpose.

## 3. DNS and SES

`terraform output dns_records` lists the records to create at the domain's DNS provider:

- an `A` record for the domain pointing at the Elastic IP. Caddy gets the HTTPS certificate as
  soon as this resolves.
- three `CNAME` records `<token>._domainkey.<domain>` for SES's DKIM. The SES identity verifies
  once they resolve, usually within an hour (`aws sesv2 get-email-identity --email-identity
  <domain>` shows `"VerifiedForSendingStatus": true`).

While the SES account is in the sandbox it only delivers to verified addresses. Verify your
personal address once (SES emails it a link):

```bash
aws sesv2 create-email-identity --email-identity you@example.com --region ap-south-1
```

Sending to students later needs SES production access (a support request in the SES console).

## 4. Parameters in SSM

The app's configuration and secrets live in SSM Parameter Store under `/neobuddy/prod/`. At every
deploy `render-env.sh` turns each parameter into one variable of `/etc/neobuddy/app.env`
(mode 600): the last path segment is the variable name. Create them as `SecureString` with the
default `aws/ssm` key. Values must not contain a single quote or a line break.

To keep values out of your shell history:

```bash
put() {  # put NAME, then type or paste the value; it is not echoed
  read -rsp "$1: " value && echo
  aws ssm put-parameter --region ap-south-1 --type SecureString --overwrite \
    --name "/neobuddy/prod/$1" --value "$value" >/dev/null
}
put NEOBUDDY_ENCRYPTION_KEY
```

| Parameter | Required | Value |
| --- | --- | --- |
| `DB_PASSWORD` | yes | Database password, e.g. `openssl rand -hex 24`. Set once; the database keeps the first one. |
| `NEOBUDDY_ENCRYPTION_KEY` | yes | `openssl rand -base64 32`. **Keep a copy outside AWS**: a restored backup is unreadable without the key it was written with. |
| `NEOBUDDY_DOMAIN` | yes | The domain, e.g. `neobuddy.example.com` (read by Caddy) |
| `NEOBUDDY_SES_FROM` | yes | Sender on the verified domain, e.g. `NeoBuddy <alerts@neobuddy.example.com>` |
| `NEOBUDDY_GOOGLE_CLIENT_ID` | yes | Client id of the student sign-in client (Google Cloud project `neobuddy-login`, type "Web application", scopes `openid email profile`, redirect URI `https://<domain>/login/oauth2/code/google`) |
| `NEOBUDDY_GOOGLE_CLIENT_SECRET` | yes | Its secret |
| `NEOBUDDY_CONTACT_EMAIL` | no | Address shown on the privacy policy and terms for questions and deletion requests |
| `NEOBUDDY_GMAIL_CLIENT_ID` | yes | OAuth client of the Google Cloud project (README, "Connect a source inbox") |
| `NEOBUDDY_GMAIL_CLIENT_SECRET` | yes | Its secret |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_SLUG` | yes | e.g. `my-college` |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_NAME` | no | Display name |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_EMAIL_DOMAINS` | yes | Comma-separated sign-in domains |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_SENDER_ALLOWLIST` | yes | Comma-separated placement-cell addresses or `@domain` |
| `NEOBUDDY_BOOTSTRAP_COLLEGE_NEO_ID_PATTERN` | no | Defaults to `^[A-Z0-9]{8}$` |
| `NEOBUDDY_BOOTSTRAP_ADMIN_EMAIL` | yes | Your college email |
| `NEOBUDDY_BOOTSTRAP_ADMIN_NEO_ID` | yes | Your NeoID |
| `NEOBUDDY_BOOTSTRAP_ADMIN_REG_NO` | no | |
| `NEOBUDDY_BOOTSTRAP_ADMIN_NTFY_TOPIC` | for push | Your ntfy topic |
| `NEOBUDDY_BOOTSTRAP_ADMIN_PERSONAL_EMAIL` | for email | Your personal address, SES-verified while in the sandbox |
| `NEOBUDDY_BOOTSTRAP_SOURCE_EMAIL` | yes | The inbox placement mail is read from |
| `NEOBUDDY_BOOTSTRAP_SOURCE_REFRESH_TOKEN` | yes | From `./gradlew authorizeSource --console=plain` on your machine |
| `NEOBUDDY_NTFY_BASE_URL` | no | Defaults to `https://ntfy.sh` |

The deploy fails, naming what is missing, when `DB_PASSWORD`, `NEOBUDDY_ENCRYPTION_KEY`,
`NEOBUDDY_DOMAIN`, `NEOBUDDY_SES_FROM` or one of the two `NEOBUDDY_GOOGLE_CLIENT_*` is absent. A changed
parameter takes effect at the next deploy (or a re-run of the last one, section 6).

## 5. Connect GitHub

In the repository's Settings, Secrets and variables, Actions, **Variables** (none is a secret),
create the four that `terraform output github_repository_variables` prints:
`AWS_DEPLOY_ROLE_ARN`, `AWS_INSTANCE_ID`, `ECR_REPOSITORY_URL`, `NEOBUDDY_DOMAIN`.

The deploy role trusts only workflows running on `main` of this repository; pull requests and
other branches cannot assume it. Until the variables exist the Deploy workflow skips itself with
a notice instead of failing; with only some of them set it fails and names the missing ones.

## 6. Deploy

Every push to `main` deploys by itself once CI is green: the Deploy workflow builds the image
with Jib for linux/arm64 and linux/amd64, pushes it to ECR tagged with the commit SHA, then over
SSM Run Command unpacks that commit's `deploy/` directory on the instance and runs
`deploy.sh`. That script renders `app.env` from SSM, pulls the image, runs
`docker compose up -d` and waits until `/actuator/health` reports UP; the workflow then checks
`https://<domain>/actuator/health` from outside. Either check failing fails the job.

The first deploy: once sections 2 to 5 are done, open Actions, Deploy, **Run workflow** on
`main` with the tag left empty (or merge anything to `main`). The first start creates the schema,
seeds the college, you and the source inbox; Caddy needs up to a minute for the certificate.

Re-running a deploy (for example after changing a parameter) is Actions, Deploy, Run workflow,
with the tag of the running image, or "Re-run all jobs" on the last run. Tags are immutable, so
an image is never rebuilt over.

## 7. Roll back

Actions, Deploy, **Run workflow** on `main`, with `image_tag` set to the full SHA of an earlier
commit whose image is in ECR (`aws ecr describe-images --repository-name neobuddy --query
'sort_by(imageDetails,&imagePushedAt)[-10:].[imageTags[0],imagePushedAt]' --output table`). It
uses the deploy scripts of the current `main`.

Without GitHub, from a Session Manager shell on the instance:

```bash
sudo /opt/neobuddy/deploy.sh <ecr-repository-url>:<sha>
```

Database migrations only go forward. An older image runs against the newer schema, which works
as long as migrations stay backward compatible (add columns and tables; never rename or drop in
the same release that stops using them). If that is not the case, restore the backup taken before
the release instead (section 8).

## 8. Backups and restore

`neobuddy-backup.timer` runs `backup.sh` every night at 02:00 IST: `pg_dump | gzip` to a local
file, checked, then uploaded to `s3://<backup-bucket>/daily/neobuddy-<timestamp>.sql.gz`. Objects
expire after 30 days. The instance may only write to the bucket; it cannot read or delete
backups, so restoring goes through a short-lived download link made with your own credentials.

Check the timer and the last run (on the instance):

```bash
systemctl list-timers neobuddy-backup.timer
journalctl -u neobuddy-backup.service -n 20
sudo systemctl start neobuddy-backup.service   # take a backup now, e.g. before a risky release
```

Restore (this replaces the whole database):

```bash
# On your machine:
aws s3 ls s3://<backup-bucket>/daily/
aws s3 presign s3://<backup-bucket>/daily/<file>.sql.gz --expires-in 900

# On the instance (aws ssm start-session --target <instance-id>), as root (sudo -i):
cd /opt/neobuddy
./compose.sh stop app
./compose.sh exec -T db psql -U neobuddy -d postgres \
  -c 'drop database neobuddy with (force)' -c 'create database neobuddy owner neobuddy'
curl -fsS '<presigned-url>' | gunzip | ./compose.sh exec -T db psql -U neobuddy -d neobuddy -v ON_ERROR_STOP=1 -q
./compose.sh start app
curl -fsS http://127.0.0.1:8080/actuator/health
```

The restored data is only readable with the `NEOBUDDY_ENCRYPTION_KEY` that was in use when the
backup was taken.

## 9. Everyday operations

```bash
aws logs tail /neobuddy/prod --follow --region ap-south-1    # all containers
aws ssm start-session --target <instance-id>                 # then: sudo -i
/opt/neobuddy/compose.sh ps
```

**Operating system updates.** Amazon Linux 2023 does not update itself. From a Session Manager
shell: `sudo dnf upgrade --releasever=latest -y && sudo reboot`. The stack restarts by itself
(`restart: unless-stopped`).

**Moving to a new instance on purpose** (a new AMI, a different size that needs a new instance,
changed user-data): take a backup (section 8), then in `ec2.tf` temporarily remove
`prevent_destroy`, disable termination protection
(`aws ec2 modify-instance-attribute --instance-id <id> --no-disable-api-termination`), run
`terraform apply -replace=aws_instance.app`, put `prevent_destroy` back, update the
`AWS_INSTANCE_ID` repository variable, deploy, and restore the backup. The Elastic IP moves with
it, so DNS stays as it is. Changing only the instance type, within the same architecture (for
example `t4g.small` to `t4g.medium`), does not need this: Terraform stops and starts the instance
with its disk.
