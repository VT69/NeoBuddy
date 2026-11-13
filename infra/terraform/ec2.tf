data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# The latest Amazon Linux 2023 for the instance type's architecture, from AWS's public parameter.
data "aws_ec2_instance_type" "app" {
  instance_type = var.instance_type
}

locals {
  arch = contains(data.aws_ec2_instance_type.app.supported_architectures, "arm64") ? "arm64" : "x86_64"
}

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-${local.arch}"
}

resource "aws_security_group" "app" {
  name        = local.name
  description = "NeoBuddy: HTTP and HTTPS in, anything out. No SSH: use SSM Session Manager."
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP, for Let's Encrypt and the redirect to HTTPS"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# Gmail, ntfy, SES, ECR, SSM, CloudWatch, S3, Docker Hub and Let's Encrypt are all outbound.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  description       = "Everything out"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.insecure_value
  instance_type          = var.instance_type
  subnet_id              = sort(data.aws_subnets.default.ids)[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name
  # The database lives on this instance's root volume: no termination by accident.
  disable_api_termination = true

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    region          = var.region
    log_group       = aws_cloudwatch_log_group.app.name
    backup_bucket   = aws_s3_bucket.backups.bucket
    ecr_registry    = local.ecr_registry
    ssm_path        = "${local.ssm_path}/"
    compose_version = var.compose_version.version
    compose_sha256  = var.compose_version.sha256
  })

  metadata_options {
    http_tokens = "required"
    # 2, not 1: the app reaches the instance role's credentials (for SES) from inside a container,
    # which is one network hop further away.
    http_put_response_hop_limit = 2
    http_endpoint               = "enabled"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size_gb
    encrypted             = true
    delete_on_termination = true
  }

  # Standard credits: a CPU-heavy spell is throttled instead of billed.
  credit_specification {
    cpu_credits = "standard"
  }

  tags = {
    Name = local.name
  }

  lifecycle {
    # Replacing the instance would destroy the database with it. A newer AMI or changed user-data
    # therefore never replaces it; deploy/README.md says how to move to a new instance on purpose.
    ignore_changes  = [ami, user_data]
    prevent_destroy = true
  }
}

resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id

  tags = {
    Name = local.name
  }
}
