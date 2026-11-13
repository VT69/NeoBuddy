variable "domain" {
  description = "Public domain NeoBuddy is served on, e.g. neobuddy.example.com. Caddy fetches its certificate; SES sends from it."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$", var.domain))
    error_message = "domain must be a lower-case DNS name such as neobuddy.example.com."
  }
}

variable "github_repository" {
  description = "owner/name of the GitHub repository allowed to deploy, exactly as GitHub spells it."
  type        = string
  default     = "VT69/NeoBuddy"
}

variable "create_github_oidc_provider" {
  description = "false if the account already has the token.actions.githubusercontent.com OIDC provider (there can be only one); it is then looked up instead."
  type        = bool
  default     = true
}

variable "region" {
  description = "AWS region for everything."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  type    = string
  default = "neobuddy"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "instance_type" {
  description = "Graviton (arm64) instance type."
  type        = string
  default     = "t4g.small"
}

variable "root_volume_size_gb" {
  description = "Encrypted gp3 root volume; also holds the PostgreSQL data volume and Docker images."
  type        = number
  default     = 20
}

variable "backup_retention_days" {
  type    = number
  default = 30
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "compose_version" {
  description = "Docker Compose plugin installed by user-data, with the SHA-256 of each architecture's binary from the release page."
  type = object({
    version = string
    sha256 = object({
      aarch64 = string
      x86_64  = string
    })
  })
  default = {
    version = "v5.6.1"
    sha256 = {
      aarch64 = "42cfa75f51ad0ccf290bad37899e0038a8c10a0069e85233ecc1d53e3ff1347d"
      x86_64  = "47737f4236e6d3ccea7291995203f8f018058783b4c3a9469119811a0b5db8c0"
    }
  }
}
