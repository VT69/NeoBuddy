# Offline checks of the configuration: AWS is mocked, nothing is created.
#   terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_vpc" {
    defaults = { id = "vpc-0123456789abcdef0" }
  }
  mock_data "aws_subnets" {
    defaults = { ids = ["subnet-bbbb", "subnet-aaaa"] }
  }
  mock_data "aws_ec2_instance_type" {
    defaults = { supported_architectures = ["arm64"] }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { insecure_value = "ami-0123456789abcdef0" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{}" }
  }
  mock_data "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }

  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = {
      arn            = "arn:aws:ecr:ap-south-1:123456789012:repository/neobuddy"
      repository_url = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/neobuddy"
    }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:ap-south-1:123456789012:log-group:/neobuddy/prod" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::neobuddy-prod-backups-123456789012" }
  }
  mock_resource "aws_instance" {
    defaults = {
      id  = "i-0123456789abcdef0"
      arn = "arn:aws:ec2:ap-south-1:123456789012:instance/i-0123456789abcdef0"
    }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/neobuddy-prod-github-deploy" }
  }
  mock_resource "aws_eip" {
    defaults = { public_ip = "203.0.113.10" }
  }
}

variables {
  domain = "neobuddy.example.com"
}

override_resource {
  target = aws_sesv2_email_identity.domain
  values = {
    dkim_signing_attributes = {
      tokens = ["tokena", "tokenb", "tokenc"]
    }
  }
}

run "instance_is_locked_down" {
  command = apply

  assert {
    condition     = aws_instance.app.instance_type == "t4g.small"
    error_message = "The instance must be a t4g.small."
  }
  assert {
    condition     = data.aws_ssm_parameter.al2023.name == "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
    error_message = "A Graviton instance must boot the arm64 Amazon Linux 2023 image."
  }
  assert {
    condition     = aws_instance.app.metadata_options[0].http_tokens == "required" && aws_instance.app.metadata_options[0].http_put_response_hop_limit == 2
    error_message = "IMDSv2 must be required, reachable from containers (hop limit 2)."
  }
  assert {
    condition     = aws_instance.app.root_block_device[0].encrypted && aws_instance.app.root_block_device[0].volume_type == "gp3"
    error_message = "The root volume holds the database and must be encrypted gp3."
  }
  assert {
    condition     = aws_instance.app.disable_api_termination
    error_message = "Termination protection must be on: the database lives on the instance."
  }
  assert {
    condition     = aws_instance.app.subnet_id == "subnet-aaaa"
    error_message = "The subnet choice must be stable (the first in sorted order)."
  }
  assert {
    condition     = aws_eip.app.instance == aws_instance.app.id && aws_eip.app.domain == "vpc"
    error_message = "The Elastic IP must be attached to the instance."
  }
}

run "only_http_and_https_are_open" {
  command = apply

  assert {
    condition = (aws_vpc_security_group_ingress_rule.http.from_port == 80
      && aws_vpc_security_group_ingress_rule.http.to_port == 80
      && aws_vpc_security_group_ingress_rule.https.from_port == 443
    && aws_vpc_security_group_ingress_rule.https.to_port == 443)
    error_message = "Ingress must be exactly TCP 80 and 443."
  }
  assert {
    condition = (aws_vpc_security_group_ingress_rule.http.ip_protocol == "tcp"
    && aws_vpc_security_group_ingress_rule.https.ip_protocol == "tcp")
    error_message = "Ingress must be TCP only."
  }
}

run "user_data_installs_a_verified_compose_and_writes_the_infra_settings" {
  command = apply

  assert {
    condition     = strcontains(aws_instance.app.user_data, "docker-compose-linux-$arch") && strcontains(aws_instance.app.user_data, "sha256sum -c -")
    error_message = "The compose plugin must be downloaded for the right architecture and checksum-verified."
  }
  assert {
    condition     = strcontains(aws_instance.app.user_data, var.compose_version.sha256.aarch64)
    error_message = "The pinned arm64 checksum must be in user-data."
  }
  assert {
    condition = alltrue([for line in [
      "AWS_REGION=ap-south-1",
      "LOG_GROUP=/neobuddy/prod",
      "BACKUP_BUCKET=neobuddy-prod-backups-123456789012",
      "ECR_REGISTRY=123456789012.dkr.ecr.ap-south-1.amazonaws.com",
      "SSM_PATH=/neobuddy/prod/",
    ] : strcontains(aws_instance.app.user_data, line)])
    error_message = "user-data must write every setting the deploy scripts read."
  }
}

run "registry_keeps_tags_and_expires_untagged_images" {
  command = apply

  assert {
    condition     = aws_ecr_repository.app.image_tag_mutability == "IMMUTABLE"
    error_message = "Tags must be immutable so a rollback gets the image that ran before."
  }
  assert {
    condition     = jsondecode(aws_ecr_lifecycle_policy.app.policy).rules[0].selection.tagStatus == "untagged"
    error_message = "The lifecycle rule must select untagged images only."
  }
  assert {
    condition     = length(jsondecode(aws_ecr_lifecycle_policy.app.policy).rules) == 1
    error_message = "Tagged images must never be expired."
  }
}

run "backups_and_logs_are_kept_30_days" {
  command = apply

  assert {
    condition     = aws_s3_bucket_versioning.backups.versioning_configuration[0].status == "Enabled"
    error_message = "The backup bucket must be versioned."
  }
  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.backups.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "Backups must be encrypted at rest."
  }
  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.backups.rule[0].expiration[0].days == 30
    error_message = "Backups must expire after 30 days."
  }
  assert {
    condition = (aws_s3_bucket_public_access_block.backups.block_public_acls
      && aws_s3_bucket_public_access_block.backups.block_public_policy
      && aws_s3_bucket_public_access_block.backups.ignore_public_acls
    && aws_s3_bucket_public_access_block.backups.restrict_public_buckets)
    error_message = "The backup bucket must block all public access."
  }
  assert {
    condition     = aws_cloudwatch_log_group.app.retention_in_days == 30
    error_message = "Logs must be kept 30 days."
  }
}

run "instance_role_is_least_privilege" {
  command = apply

  assert {
    condition = toset(flatten([
      for s in data.aws_iam_policy_document.instance.statement : s.resources if s.sid == "ReadOwnParameters"
      ])) == toset([
      "arn:aws:ssm:ap-south-1:123456789012:parameter/neobuddy/prod",
      "arn:aws:ssm:ap-south-1:123456789012:parameter/neobuddy/prod/*",
    ])
    error_message = "The instance may read /neobuddy/prod/* and nothing else from SSM."
  }
  assert {
    condition = flatten([
      for s in data.aws_iam_policy_document.instance.statement : s.actions if s.sid == "WriteBackups"
    ]) == ["s3:PutObject"]
    error_message = "The instance may only write backups, never read or delete them."
  }
  assert {
    condition = alltrue([
      for s in data.aws_iam_policy_document.instance.statement :
      !contains(s.actions, "ssm:GetParameter") && !contains(s.actions, "ssm:GetParameters")
    ])
    error_message = "No statement may grant reading arbitrary parameters."
  }
}

run "only_main_of_this_repository_can_deploy" {
  command = apply

  assert {
    condition = flatten([
      for c in data.aws_iam_policy_document.github_assume.statement[0].condition : c.values
      if c.variable == "token.actions.githubusercontent.com:sub"
    ]) == ["repo:VT69/NeoBuddy:ref:refs/heads/main"]
    error_message = "The deploy role must trust only the main branch of VT69/NeoBuddy."
  }
  assert {
    condition = flatten([
      for s in data.aws_iam_policy_document.deploy.statement : s.resources if s.sid == "RunDeployCommand"
      ]) == [
      "arn:aws:ec2:ap-south-1:123456789012:instance/i-0123456789abcdef0",
      "arn:aws:ssm:ap-south-1::document/AWS-RunShellScript",
    ]
    error_message = "The deploy role may run only AWS-RunShellScript, only on the NeoBuddy instance."
  }
  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 1
    error_message = "The OIDC provider is created by default."
  }
}

run "an_existing_oidc_provider_is_reused" {
  command = apply

  variables {
    create_github_oidc_provider = false
  }

  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0 && length(data.aws_iam_openid_connect_provider.github) == 1
    error_message = "With create_github_oidc_provider = false the existing provider must be looked up, not created."
  }
}

run "outputs_list_the_dns_records_to_create" {
  command = apply

  assert {
    condition     = output.dns_records[0] == { name = "neobuddy.example.com", type = "A", value = "203.0.113.10" }
    error_message = "The first record must be the A record for the Elastic IP."
  }
  assert {
    condition = slice(output.dns_records, 1, 4) == [
      { name = "tokena._domainkey.neobuddy.example.com", type = "CNAME", value = "tokena.dkim.amazonses.com" },
      { name = "tokenb._domainkey.neobuddy.example.com", type = "CNAME", value = "tokenb.dkim.amazonses.com" },
      { name = "tokenc._domainkey.neobuddy.example.com", type = "CNAME", value = "tokenc.dkim.amazonses.com" },
    ]
    error_message = "The DKIM CNAMEs must follow SES's token._domainkey format."
  }
}

run "the_domain_must_be_a_dns_name" {
  command = plan

  variables {
    domain = "https://Neobuddy.example.com/"
  }

  expect_failures = [var.domain]
}
