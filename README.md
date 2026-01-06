# PIPOUMONEY Economy Plugin
A economy system plugin
## config.yml
```yaml
# ============================================================
# PipouMoney - Main Configuration
# ============================================================
# Controls economy behavior, storage, commands, audit
# and security (anti-abuse) for the PipouMoney plugin.
#
# Changes apply after: /money admin reload
# ============================================================


# ============================================================
# Number formatting & currency
# ============================================================
format:
  # Number of decimal places supported and displayed.
  # Recommended:
  #  - 2 : classic economy
  #  - 0 : integer-only economy
  decimals: 2

  # Locale used for number formatting.
  # Examples: en_US, fr_FR, de_DE
  locale: "en_US"

currency:
  # Currency symbol displayed to players.
  symbol: "$"

  # Currency name (singular / plural).
  singular: "dollar"
  plural: "dollars"

  # Display format.
  # Placeholders:
  #  {amount}  formatted numeric value
  #  {symbol}  currency symbol
  format: "{symbol}{amount}"


# ============================================================
# Autosave & database flushing
# ============================================================
autosave:
  # Automatically flush dirty accounts at a fixed interval.
  enabled: true

  # Interval in minutes between autosave flushes.
  interval-minutes: 5

flush:
  # When dirty accounts reach this threshold,
  # an async flush is queued automatically.
  dirty-threshold: 50


# ============================================================
# Player listeners
# ============================================================
listeners:
  # Update player name in database on join.
  update-name-on-join: true

  # Allow flushing player data on quit if needed.
  flush-on-quit: true


# ============================================================
# Pay system (/pay)
# ============================================================
pay:
  # Enable all payment features.
  enabled: true

  # Minimum transfer amount.
  min: 1.0

  # Allow paying yourself.
  allow-pay-self: false

  # Cooldown between payments (seconds).
  cooldown-seconds: 3

  # Require confirmation above this amount.
  # Set to 0 to disable confirmation.
  confirm-above: 500.0

  # Confirmation expiration (seconds).
  confirm-timeout-seconds: 20

  # Payment request expiration (seconds).
  request-timeout-seconds: 60

  # Percentage tax applied to payments.
  tax-percent: 0.0

  # Tax mode:
  #  - sink     : tax is removed from economy
  #  - treasury : tax is sent to treasury account
  tax-mode: "sink"

  # Treasury UUID (required if tax-mode = treasury).
  treasury-uuid: "00000000-0000-0000-0000-000000000000"


# ============================================================
# Balances listing (/money admin balances)
# ============================================================
balances:
  # If true, list only ONLINE players.
  only-online: false

  # Minimum balance to appear.
  min: 0.0

  # Entries per page.
  per-page: 10

  # Show UUID next to player name.
  show-uuid: false

  # Sorting mode:
  #  BAL_DESC, BAL_ASC, NAME_ASC, NAME_DESC
  sort: "BAL_DESC"


# ============================================================
# Top command (/baltop, /money admin top)
# ============================================================
top:
  # Default number of entries.
  default: 10

  # Maximum allowed entries.
  max: 50


# ============================================================
# Top cache (performance)
# ============================================================
top-cache:
  # Enable in-memory cache for top balances.
  enabled: true

  # Number of cached entries.
  size: 50

  # Cache refresh interval (minutes).
  refresh-minutes: 5


# ============================================================
# Audit & transaction history
# ============================================================
audit:
  # Enable transaction auditing.
  enabled: true

  # Results per page for history queries.
  per-page: 10

  purge-on-start:
    # Purge old audit entries at startup.
    enabled: false

    # Delete entries older than X days.
    older-than-days: 90


# ============================================================
# Player history limits
# ============================================================
player:
  # Maximum number of days a player can query.
  history-days-limit: 30

  # Absolute cap on returned results.
  history-max-results: 200


# ============================================================
# Anti-abuse / fraud detection
# ============================================================
anti-abuse:
  # Master switch.
  enabled: true

  # Notify online admins when triggered.
  alert-admins: true

  # Block the transaction when triggered.
  block-on-trigger: false

  # Automatically flag the transaction in audit logs.
  auto-flag: true

  # Maximum transactions allowed in a short window.
  max-transactions-per-window: 10

  # Time window size (seconds).
  window-seconds: 60

  # Maximum total amount allowed in a window.
  window-max-amount: 5000.0

  # Maximum amount allowed per day.
  daily-max-amount: 25000.0

  # Maximum single transaction amount.
  single-tx-max-amount: 10000.0


# ============================================================
# Health command
# ============================================================
health:
  # TPS sample index:
  #  0 = 1 minute
  #  1 = 5 minutes
  #  2 = 15 minutes
  tps-sample: 0
```
