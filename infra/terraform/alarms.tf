# ---- Alarms ----------------------------------------------------------------------------------
#
# Three alarms, each emailing the address in var.alarm_email through SNS:
#
# 1. The instance's EC2 status checks (system and instance). Free 1-minute metrics.
# 2. The public health endpoint, probed from outside by a Route 53 health check. Stopping the app
#    container makes Caddy answer 502; a DOWN app answers 503; either fails the check.
# 3. Deliveries given up: a metric filter counts the app's {"event":"DeliveryDead"} JSON log lines.
#
# Why Route 53 and not CloudWatch Synthetics for the external check: a Route 53 HTTPS health check
# costs about $1.50 a month ($0.50 for the check plus $1.00 for HTTPS; up to $2.75 if AWS bills it
# as a non-AWS endpoint) and probes every 30 seconds from several regions. A Synthetics canary
# costs $0.0012 per run, so once every 5 minutes is about $10 a month before its Lambda, S3 and
# log costs, and once a minute about $52. Route 53 can only check status codes (and optionally a
# string), which is all /actuator/health needs: it answers 503 when DOWN.
#
# Route 53 publishes health check metrics in us-east-1 only, so that alarm and its SNS topic live
# there; confirming the subscription email is needed once per topic (two emails).

provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

resource "aws_sns_topic" "alarms" {
  name = "${local.name}-alarms"
}

resource "aws_sns_topic_subscription" "alarms_email" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

resource "aws_sns_topic" "alarms_us_east_1" {
  provider = aws.us_east_1
  name     = "${local.name}-alarms"
}

resource "aws_sns_topic_subscription" "alarms_us_east_1_email" {
  provider  = aws.us_east_1
  topic_arn = aws_sns_topic.alarms_us_east_1.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# 1. EC2 status checks ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "instance_status" {
  alarm_name          = "${local.name}-instance-status-check"
  alarm_description   = "The EC2 instance or its host failed a status check for 3 minutes. See deploy/README.md, Alarms."
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed"
  dimensions          = { InstanceId = aws_instance.app.id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# 2. External health check -----------------------------------------------------------------------

resource "aws_route53_health_check" "app" {
  fqdn              = var.domain
  type              = "HTTPS"
  port              = 443
  resource_path     = "/actuator/health"
  enable_sni        = true
  request_interval  = 30
  failure_threshold = 3

  tags = { Name = "${local.name}-health" }
}

resource "aws_cloudwatch_metric_alarm" "health" {
  provider            = aws.us_east_1
  alarm_name          = "${local.name}-health-check"
  alarm_description   = "https://${var.domain}/actuator/health has not answered 2xx from outside for 2 minutes. See deploy/README.md, Alarms."
  namespace           = "AWS/Route53"
  metric_name         = "HealthCheckStatus"
  dimensions          = { HealthCheckId = aws_route53_health_check.app.id }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms_us_east_1.arn]
  ok_actions          = [aws_sns_topic.alarms_us_east_1.arn]
}

# 3. Dead deliveries -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_metric_filter" "delivery_dead" {
  name           = "${local.name}-delivery-dead"
  log_group_name = aws_cloudwatch_log_group.app.name
  # The app's JSON log line for a delivery given up carries "event":"DeliveryDead".
  pattern = "{ $.event = \"DeliveryDead\" }"

  metric_transformation {
    name      = "DeliveriesDead"
    namespace = "NeoBuddy"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "delivery_dead" {
  alarm_name          = "${local.name}-delivery-dead"
  alarm_description   = "At least ${var.dead_delivery_alarm_threshold} alert(s) went DEAD within 5 minutes. Review them at /admin/deliveries."
  namespace           = "NeoBuddy"
  metric_name         = aws_cloudwatch_log_metric_filter.delivery_dead.metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = var.dead_delivery_alarm_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  # No log line means nothing died.
  treat_missing_data = "notBreaching"
  alarm_actions      = [aws_sns_topic.alarms.arn]
}
