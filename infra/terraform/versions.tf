terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # State lives in S3 with a DynamoDB lock table, both created once by hand (deploy/README.md,
  # "One-time state bootstrap"). Their names are account-specific, so they come from backend.hcl:
  #   terraform init -backend-config=backend.hcl
  backend "s3" {
    key     = "neobuddy/prod/terraform.tfstate"
    encrypt = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
