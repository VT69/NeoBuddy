output "instance_id" {
  description = "For Session Manager (aws ssm start-session --target ...) and the AWS_INSTANCE_ID repository variable."
  value       = aws_instance.app.id
}

output "public_ip" {
  description = "The Elastic IP; the domain's A record points here."
  value       = aws_eip.app.public_ip
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "deploy_role_arn" {
  value = aws_iam_role.deploy.arn
}

output "backup_bucket" {
  value = aws_s3_bucket.backups.bucket
}

output "log_group" {
  value = aws_cloudwatch_log_group.app.name
}

output "ssm_parameter_path" {
  description = "Create the app's parameters under this path (deploy/README.md lists them)."
  value       = "${local.ssm_path}/"
}

output "github_repository_variables" {
  description = "Repository variables (Settings > Secrets and variables > Actions > Variables) the deploy workflow reads. None of them is a secret."
  value = {
    AWS_DEPLOY_ROLE_ARN = aws_iam_role.deploy.arn
    AWS_INSTANCE_ID     = aws_instance.app.id
    ECR_REPOSITORY_URL  = aws_ecr_repository.app.repository_url
    NEOBUDDY_DOMAIN     = var.domain
  }
}

output "dns_records" {
  description = "Records the domain needs at its DNS provider: the A record for the site, and SES's DKIM CNAMEs. Terraform does not create them."
  value = concat(
    [{ name = var.domain, type = "A", value = aws_eip.app.public_ip }],
    [for token in aws_sesv2_email_identity.domain.dkim_signing_attributes[0].tokens : {
      name  = "${token}._domainkey.${var.domain}"
      type  = "CNAME"
      value = "${token}.dkim.amazonses.com"
    }],
  )
}
